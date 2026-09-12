package com.middleberth.booking;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.middleberth.booking.domain.BookingStatus;
import com.middleberth.booking.domain.Seat;
import com.middleberth.booking.domain.SeatStatus;
import com.middleberth.booking.domain.Train;
import com.middleberth.booking.dto.BookingCommand;
import com.middleberth.booking.repository.BookingRepository;
import com.middleberth.booking.repository.OutboxRepository;
import com.middleberth.booking.repository.SeatRepository;
import com.middleberth.booking.repository.TrainRepository;
import com.middleberth.booking.service.BookingPayments;
import com.middleberth.booking.service.BookingService;
import com.middleberth.booking.service.HoldExpiryJob;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.kafka.KafkaConnectionDetails;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 6: the outbox.
 *
 * Saving the booking and telling Kafka used to be two separate things. If the pod
 * died in between, the booking existed and nobody was ever told. Now the note is
 * written into a table in the same transaction as the booking, and a job puts it
 * on Kafka afterwards — so the mail can be late, but it cannot be lost.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class OutboxTest {

    private static final LocalDate DATE = LocalDate.of(2026, 8, 25);

    @Autowired BookingService bookingService;
    @Autowired BookingPayments payments;
    @Autowired HoldExpiryJob expiryJob;
    @Autowired BookingRepository bookingRepo;
    @Autowired OutboxRepository outboxRepo;
    @Autowired SeatRepository seatRepo;
    @Autowired TrainRepository trainRepo;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;
    @Autowired KafkaConnectionDetails kafka;

    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void seed() {
        TestDatabase.wipe(jdbc);
        tx.executeWithoutResult(s -> {
            Train train = trainRepo.save(new Train("12951", "Mumbai Rajdhani"));
            seatRepo.save(new Seat(train.getId(), DATE, "3A", "B2", "31", SeatStatus.FREE));
        });
    }

    /**
     * The whole point of the pattern. If the booking does not survive, neither does
     * the note — nobody is ever told about a ticket that was rolled back.
     */
    @Test
    void a_note_that_loses_its_transaction_is_never_sent() {
        book("A7X2", 5512);

        tx.executeWithoutResult(s -> {
            payments.apply(5512L, "A7X2", Instant.now());
            s.setRollbackOnly();                        // the pod dies here
        });

        assertThat(bookingRepo.findByUserIdAndRequestId(5512L, "A7X2").orElseThrow().getStatus())
                .isEqualTo(BookingStatus.HELD);
        assertThat(outboxRepo.count()).as("no note without a booking to go with it").isZero();
    }

    @Test
    void a_confirmed_ticket_is_written_down_and_then_sent() throws Exception {
        book("A7X2", 5512);

        try (Consumer<String, String> consumer = bookingEvents()) {
            payments.apply(5512L, "A7X2", Instant.now());

            assertThat(outboxRepo.count()).as("written with the booking, before any Kafka").isEqualTo(1);

            ConsumerRecord<String, String> record = awaitOne(consumer);
            assertThat(record.key()).isEqualTo("5512|A7X2");
            JsonNode event = json.readTree(record.value());
            assertThat(event.get("type").asText()).isEqualTo("TICKET_CONFIRMED");
            assertThat(event.get("berth").asText()).isEqualTo("B2-31");
            assertThat(event.get("trainNumber").asText()).isEqualTo("12951");
        }

        await(() -> outboxRepo.findAll().get(0).getSentAt() != null, "the note is marked sent");
    }

    /** The mail nobody was expecting: a berth came free and the next waitlister already paid. */
    @Test
    void a_promoted_waitlister_gets_a_ticket_note() throws Exception {
        book("HOLDER", 1);                               // the only berth
        book("WAITER", 2);
        payments.apply(2L, "WAITER", Instant.now());     // paid, WAITLISTED
        outboxRepo.deleteAllInBatch();                   // ignore anything written so far

        try (Consumer<String, String> consumer = bookingEvents()) {
            expiryJob.releaseExpired(Instant.now().plus(Duration.ofMinutes(30)));

            JsonNode event = json.readTree(awaitOne(consumer).value());
            assertThat(event.get("type").asText()).isEqualTo("TICKET_CONFIRMED");
            assertThat(event.get("requestId").asText()).isEqualTo("WAITER");
            assertThat(event.get("berth").asText()).isEqualTo("B2-31");
        }
    }

    @Test
    void a_booking_that_has_to_be_refunded_gets_a_cancelled_note() throws Exception {
        book("LATE", 1);
        Instant tooLate = bookingRepo.findByUserIdAndRequestId(1L, "LATE").orElseThrow()
                .getPayBy().plus(Duration.ofMinutes(1));
        expiryJob.releaseExpired(Instant.now().plus(Duration.ofMinutes(30)));
        outboxRepo.deleteAllInBatch();

        try (Consumer<String, String> consumer = bookingEvents()) {
            payments.apply(1L, "LATE", tooLate);

            JsonNode event = json.readTree(awaitOne(consumer).value());
            assertThat(event.get("type").asText()).isEqualTo("BOOKING_CANCELLED");
            assertThat(event.get("reason").asText()).isEqualTo("PAID_AFTER_DEADLINE");
            assertThat(event.get("berth").isNull()).as("there is no berth to name").isTrue();
        }
    }

    /** notification-service reads this same file to check its side. */
    @Test
    void what_it_publishes_matches_the_shared_contract() throws Exception {
        JsonNode contract = json.readTree(Path.of("../contracts/booking-event.json").toFile());
        book("A7X2", 5512);

        try (Consumer<String, String> consumer = bookingEvents()) {
            payments.apply(5512L, "A7X2", Instant.now());
            JsonNode published = json.readTree(awaitOne(consumer).value());

            contract.fieldNames().forEachRemaining(field ->
                    assertThat(published.has(field))
                            .as("booking-service publishes '%s', which notification-service reads", field)
                            .isTrue());
            assertThat(published.get("type").asText()).isEqualTo(contract.get("type").asText());
        }
    }

    // ---------- helpers ----------

    private void book(String requestId, long userId) {
        bookingService.book(new BookingCommand(requestId, userId, "12951", DATE, "3A", TestPassenger.SOMEONE));
    }

    /** Plays notification-service: listens from now on. */
    private Consumer<String, String> bookingEvents() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        Consumer<String, String> consumer = new KafkaConsumer<>(props);
        List<TopicPartition> partitions = consumer.partitionsFor("booking-events").stream()
                .map(p -> new TopicPartition(p.topic(), p.partition())).toList();
        consumer.assign(partitions);
        consumer.seekToEnd(partitions);
        partitions.forEach(consumer::position);
        return consumer;
    }

    private ConsumerRecord<String, String> awaitOne(Consumer<String, String> consumer) {
        List<ConsumerRecord<String, String>> out = new ArrayList<>();
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < deadline && out.isEmpty()) {
            consumer.poll(Duration.ofMillis(200)).forEach(out::add);
        }
        assertThat(out).as("the outbox job sent it").hasSize(1);
        return out.get(0);
    }

    private void await(java.util.function.BooleanSupplier condition, String what) {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) return;
            try { Thread.sleep(50); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
        }
        assertThat(condition.getAsBoolean()).as(what).isTrue();
    }
}
