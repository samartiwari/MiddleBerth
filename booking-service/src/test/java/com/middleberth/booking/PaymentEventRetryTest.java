package com.middleberth.booking;

import com.middleberth.booking.domain.BookingStatus;
import com.middleberth.booking.domain.Seat;
import com.middleberth.booking.domain.SeatStatus;
import com.middleberth.booking.domain.Train;
import com.middleberth.booking.dto.BookingCommand;
import com.middleberth.booking.kafka.PaymentEventsConfig;
import com.middleberth.booking.repository.BookingRepository;
import com.middleberth.booking.repository.SeatRepository;
import com.middleberth.booking.repository.TrainRepository;
import com.middleberth.booking.service.BookingPayments;
import com.middleberth.booking.service.BookingService;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.kafka.KafkaConnectionDetails;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * A payment event must never be silently dropped.
 *
 * Spring Kafka's default retries a failing message 10 times — about half a second
 * apart, so roughly 4.5 seconds in all (measured) — and then throws it away. A
 * database restart takes longer than that, and would lose a customer's payment
 * confirmation for good. These tests make confirmation fail on purpose.
 *
 * Its own class: spying on BookingPayments and shrinking the retry timings both
 * change the Spring context. Real timings span about two minutes; here about 13
 * seconds, so the tests stay quick but still outlast the default's 4.5.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@TestPropertySource(properties = {
        "middleberth.payment-events.retry.initial-interval=PT0.5S",   // 0.5, 1, 2, 2, 2 ... ≈ 13s in all
        "middleberth.payment-events.retry.max-interval=PT2S",
        "middleberth.payment-events.retry.max-attempts=8"
})
class PaymentEventRetryTest {

    private static final LocalDate DATE = LocalDate.of(2026, 8, 25);

    @MockitoSpyBean BookingPayments payments;
    @Autowired BookingService bookingService;
    @Autowired BookingRepository bookingRepo;
    @Autowired SeatRepository seatRepo;
    @Autowired TrainRepository trainRepo;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;
    @Autowired KafkaConnectionDetails kafka;

    @BeforeEach
    void seed() {
        TestDatabase.wipe(jdbc);
        tx.executeWithoutResult(s -> {
            Train train = trainRepo.save(new Train("12951", "Mumbai Rajdhani"));
            seatRepo.save(new Seat(train.getId(), DATE, "3A", "B2", "1", SeatStatus.FREE));
        });
    }

    /**
     * The database is unreachable for 8 seconds — a restart, say. An outage is a
     * stretch of TIME, not a number of attempts, so it is modelled that way.
     *
     * Spring's default spends its 10 attempts over about 4.5 seconds, all inside
     * the outage, and drops the event. With growing gaps, the later retries land
     * after the database is back.
     */
    @Test
    void a_database_blip_is_ridden_out_and_the_booking_is_confirmed() {
        bookingService.book(new BookingCommand("A7X2", 5512L, "12951", DATE, "3A", TestPassenger.SOMEONE));
        Instant databaseBack = Instant.now().plusSeconds(8);
        doAnswer(call -> {
            if (Instant.now().isBefore(databaseBack)) {
                throw new TransientDataAccessResourceException("database unreachable");
            }
            return call.callRealMethod();
        }).when(payments).apply(any(), any(), any());

        publish("5512|A7X2", paid(5512, "A7X2"));

        awaitConfirmed(5512, "A7X2");
    }

    @Test
    void a_payment_that_keeps_failing_is_parked_on_the_dead_letter_topic_not_dropped() {
        bookingService.book(new BookingCommand("A7X2", 5512L, "12951", DATE, "3A", TestPassenger.SOMEONE));
        doThrow(new TransientDataAccessResourceException("database down for good"))
                .when(payments).apply(any(), any(), any());

        try (Consumer<String, String> deadLetters = consumerFromNow(PaymentEventsConfig.PAYMENT_EVENTS_DLT)) {
            publish("5512|A7X2", paid(5512, "A7X2"));

            List<ConsumerRecord<String, String>> parked = drain(deadLetters, 1, Duration.ofSeconds(30));
            assertThat(parked).as("kept, so it can be replayed").hasSize(1);
            assertThat(parked.get(0).value()).contains("A7X2").contains("PAID");
        }
        assertThat(bookingRepo.findByUserIdAndRequestId(5512L, "A7X2").orElseThrow().getStatus())
                .as("not confirmed yet — but not lost either").isEqualTo(BookingStatus.HELD);
    }

    /** Not even valid JSON. Must not trip the listener over and over. */
    @Test
    void a_message_that_is_not_json_is_parked_too() {
        try (Consumer<String, String> deadLetters = consumerFromNow(PaymentEventsConfig.PAYMENT_EVENTS_DLT)) {
            publish("x|y", "this is not json");

            assertThat(drain(deadLetters, 1, Duration.ofSeconds(30))).hasSize(1);
        }
    }

    // ---------- helpers ----------

    private String paid(long userId, String requestId) {
        return """
                {"type":"PAID","userId":%d,"requestId":"%s","orderId":"order_x","paymentId":"pay_x",\
                "amountPaise":240000,"paidAt":"%s"}""".formatted(userId, requestId, Instant.now());
    }

    private void publish(String key, String value) {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        try (Producer<String, String> producer = new KafkaProducer<>(props)) {
            producer.send(new ProducerRecord<>(PaymentEventsConfig.PAYMENT_EVENTS, key, value));
            producer.flush();
        }
    }

    private void awaitConfirmed(long userId, String requestId) {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        BookingStatus status = null;
        while (System.nanoTime() < deadline) {
            status = bookingRepo.findByUserIdAndRequestId(userId, requestId).orElseThrow().getStatus();
            if (status == BookingStatus.CONFIRMED) return;
            try { Thread.sleep(50); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
        }
        assertThat(status).isEqualTo(BookingStatus.CONFIRMED);
    }

    private Consumer<String, String> consumerFromNow(String topic) {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        Consumer<String, String> consumer = new KafkaConsumer<>(props);
        List<TopicPartition> partitions = consumer.partitionsFor(topic).stream()
                .map(p -> new TopicPartition(p.topic(), p.partition())).toList();
        consumer.assign(partitions);
        consumer.seekToEnd(partitions);
        partitions.forEach(consumer::position);
        return consumer;
    }

    private List<ConsumerRecord<String, String>> drain(Consumer<String, String> consumer, int expected, Duration timeout) {
        List<ConsumerRecord<String, String>> out = new ArrayList<>();
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline && out.size() < expected) {
            consumer.poll(Duration.ofMillis(200)).forEach(out::add);
        }
        return out;
    }
}
