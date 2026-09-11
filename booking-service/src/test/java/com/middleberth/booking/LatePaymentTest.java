package com.middleberth.booking;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.middleberth.booking.domain.Booking;
import com.middleberth.booking.domain.BookingStatus;
import com.middleberth.booking.domain.Seat;
import com.middleberth.booking.domain.SeatStatus;
import com.middleberth.booking.domain.Train;
import com.middleberth.booking.dto.BookingCommand;
import com.middleberth.booking.repository.BookingRepository;
import com.middleberth.booking.repository.SeatRepository;
import com.middleberth.booking.repository.TrainRepository;
import com.middleberth.booking.service.BookingService;
import com.middleberth.booking.service.HoldExpiryJob;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
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
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.*;
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
 * Phase 5d: money that turns up late.
 *
 * A customer can pay at 4 minutes 58 seconds and have the bank, Razorpay and our
 * own queue take another minute to tell us. By then the hold is gone. Throwing that
 * money away is the one thing we must never do, so:
 *
 *   paid BEFORE the deadline   honour it — a free berth, or a place on the waitlist
 *                              and only if there is neither, give the money back
 *   paid AFTER the deadline    give the money back
 *
 * And a hold that is mid-payment gets a few extra minutes before it is released, so
 * the common case never becomes a late payment at all.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LatePaymentTest {

    private static final LocalDate DATE = LocalDate.of(2026, 8, 25);

    @Autowired TestRestTemplate http;
    @Autowired BookingService bookingService;
    @Autowired HoldExpiryJob expiryJob;
    @Autowired BookingRepository bookingRepo;
    @Autowired SeatRepository seatRepo;
    @Autowired TrainRepository trainRepo;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;
    @Autowired KafkaConnectionDetails kafka;
    @Autowired StubPaymentServer paymentService;

    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void seed() {
        TestDatabase.wipe(jdbc);
        paymentService.reset();
        tx.executeWithoutResult(s -> {
            Train train = trainRepo.save(new Train("12951", "Mumbai Rajdhani"));
            for (int n = 1; n <= 2; n++) {
                seatRepo.save(new Seat(train.getId(), DATE, "3A", "B2", String.valueOf(n), SeatStatus.FREE));
            }
        });
    }

    // ---------- the extra time ----------

    /**
     * Someone on the Razorpay screen with their card in hand is not someone who has
     * given up. Clicking Pay Now marks the hold, and the release job then leaves it
     * alone for a few minutes more.
     */
    @Test
    void a_hold_being_paid_for_gets_extra_time() {
        book("PAYING", 1);
        book("IDLE", 2);
        payNow("PAYING", 1);

        expiryJob.releaseExpired(Instant.now().plus(Duration.ofMinutes(7)));

        assertThat(status("IDLE", 2)).as("never started paying").isEqualTo(BookingStatus.EXPIRED);
        assertThat(status("PAYING", 1)).as("inside the grace window").isEqualTo(BookingStatus.HELD);

        expiryJob.releaseExpired(Instant.now().plus(Duration.ofMinutes(10)));

        assertThat(status("PAYING", 1)).as("grace ran out too").isEqualTo(BookingStatus.EXPIRED);
    }

    // ---------- paid in time, told late ----------

    @Test
    void money_that_arrived_in_time_still_gets_a_berth() {
        book("LATE", 1);
        expire();

        publish("1|LATE", paid(1, "LATE", justBeforeDeadline(1, "LATE")));

        Booking b = awaitStatus("LATE", 1, BookingStatus.CONFIRMED);
        assertThat(seatRepo.findById(b.getSeatId()).orElseThrow().getStatus()).isEqualTo(SeatStatus.CONFIRMED);
    }

    @Test
    void money_that_arrived_in_time_gets_a_waitlist_place_when_the_berths_are_gone() {
        book("LATE", 1);
        Instant inTime = justBeforeDeadline(1, "LATE");
        expire();
        book("X", 2);
        book("Y", 3);                                     // both berths, taken while we were not looking

        publish("1|LATE", paid(1, "LATE", inTime));

        assertThat(awaitStatus("LATE", 1, BookingStatus.WAITLISTED).getWaitlistPos()).isEqualTo(1);
    }

    @Test
    void money_that_arrived_in_time_with_nothing_left_at_all_is_refunded() throws Exception {
        book("LATE", 1);
        Instant inTime = justBeforeDeadline(1, "LATE");
        expire();
        book("X", 2);
        book("Y", 3);                                     // berths gone
        book("W1", 4);
        book("W2", 5);                                    // waitlist full as well

        try (Consumer<String, String> refunds = refundRequests()) {
            publish("1|LATE", paid(1, "LATE", inTime));

            JsonNode refund = json.readTree(awaitRefund(refunds).value());
            assertThat(refund.get("reason").asText()).isEqualTo("NOTHING_LEFT");
            assertThat(refund.get("userId").asLong()).isEqualTo(1);
        }
        assertThat(status("LATE", 1)).isEqualTo(BookingStatus.EXPIRED);
    }

    // ---------- paid too late ----------

    @Test
    void money_that_arrived_after_the_deadline_is_refunded() throws Exception {
        book("LATE", 1);
        Instant tooLate = deadline(1, "LATE").plus(Duration.ofMinutes(1));
        expire();

        try (Consumer<String, String> refunds = refundRequests()) {
            publish("1|LATE", paid(1, "LATE", tooLate));

            JsonNode refund = json.readTree(awaitRefund(refunds).value());
            assertThat(refund.get("reason").asText()).isEqualTo("PAID_AFTER_DEADLINE");
        }
        assertThat(status("LATE", 1))
                .as("a berth was free, but they paid after their time was up").isEqualTo(BookingStatus.EXPIRED);
    }

    /** The other side of the contract: payment-service reads this file to check what it accepts. */
    @Test
    void the_refund_request_matches_the_shared_contract() throws Exception {
        JsonNode contract = json.readTree(Path.of("../contracts/refund-request.json").toFile());
        book("A7X2", 5512);
        Instant tooLate = deadline(5512, "A7X2").plus(Duration.ofMinutes(1));
        expire();

        try (Consumer<String, String> refunds = refundRequests()) {
            publish("5512|A7X2", paid(5512, "A7X2", tooLate));
            ConsumerRecord<String, String> record = awaitRefund(refunds);

            assertThat(record.key()).as("keyed by booking, so one booking is handled by one thread")
                    .isEqualTo("5512|A7X2");
            JsonNode published = json.readTree(record.value());
            contract.fieldNames().forEachRemaining(field -> {
                assertThat(published.has(field))
                        .as("booking-service asks with '%s', which payment-service reads", field).isTrue();
                assertThat(published.get(field).getNodeType())
                        .as("'%s' has the type payment-service expects", field)
                        .isEqualTo(contract.get(field).getNodeType());
            });
        }
    }

    // ---------- helpers ----------

    private void book(String requestId, long userId) {
        bookingService.book(new BookingCommand(requestId, userId, "12951", DATE, "3A"));
    }

    private void payNow(String requestId, long userId) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-User-Id", String.valueOf(userId));
        ResponseEntity<String> res = http.exchange("/api/bookings/" + requestId + "/pay",
                HttpMethod.POST, new HttpEntity<>(headers), String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    /** Long past every deadline, so every hold in the test is released. */
    private void expire() {
        expiryJob.releaseExpired(Instant.now().plus(Duration.ofMinutes(30)));
    }

    private Instant deadline(long userId, String requestId) {
        return bookingRepo.findByUserIdAndRequestId(userId, requestId).orElseThrow().getPayBy();
    }

    private Instant justBeforeDeadline(long userId, String requestId) {
        return deadline(userId, requestId).minusSeconds(2);
    }

    private BookingStatus status(String requestId, long userId) {
        return bookingRepo.findByUserIdAndRequestId(userId, requestId).orElseThrow().getStatus();
    }

    private String paid(long userId, String requestId, Instant paidAt) {
        return """
                {"type":"PAID","userId":%d,"requestId":"%s","orderId":"order_x","paymentId":"pay_x",\
                "amountPaise":240000,"paidAt":"%s"}""".formatted(userId, requestId, paidAt);
    }

    private void publish(String key, String body) {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        try (Producer<String, String> producer = new KafkaProducer<>(props)) {
            producer.send(new ProducerRecord<>("payment-events", key, body));
            producer.flush();
        }
    }

    /** Plays payment-service: listens for refund requests from now on. */
    private Consumer<String, String> refundRequests() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        Consumer<String, String> consumer = new KafkaConsumer<>(props);
        List<TopicPartition> partitions = consumer.partitionsFor("refund-requests").stream()
                .map(p -> new TopicPartition(p.topic(), p.partition())).toList();
        consumer.assign(partitions);
        consumer.seekToEnd(partitions);
        partitions.forEach(consumer::position);
        return consumer;
    }

    private ConsumerRecord<String, String> awaitRefund(Consumer<String, String> consumer) {
        List<ConsumerRecord<String, String>> out = new ArrayList<>();
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < deadline && out.isEmpty()) {
            consumer.poll(Duration.ofMillis(200)).forEach(out::add);
        }
        assertThat(out).as("a refund was asked for").hasSize(1);
        return out.get(0);
    }

    private Booking awaitStatus(String requestId, long userId, BookingStatus expected) {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        Booking b = null;
        while (System.nanoTime() < deadline) {
            b = bookingRepo.findByUserIdAndRequestId(userId, requestId).orElse(null);
            if (b != null && b.getStatus() == expected) return b;
            try { Thread.sleep(50); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
        }
        assertThat(b).isNotNull();
        assertThat(b.getStatus()).as("booking %s/%s", userId, requestId).isEqualTo(expected);
        return b;
    }
}
