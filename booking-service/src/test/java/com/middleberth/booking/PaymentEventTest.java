package com.middleberth.booking;

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
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.kafka.KafkaConnectionDetails;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 5c: "paid" from payment-service turns a hold into a confirmed booking.
 *
 * The tests stand in for payment-service and publish the event themselves.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class PaymentEventTest {

    private static final LocalDate DATE = LocalDate.of(2026, 8, 25);

    @Autowired BookingService bookingService;
    @Autowired HoldExpiryJob expiryJob;
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
            for (int n = 1; n <= 2; n++) {
                seatRepo.save(new Seat(train.getId(), DATE, "3A", "B2", String.valueOf(n), SeatStatus.FREE));
            }
        });
    }

    /**
     * booking-service's half of the payment-event contract. payment-service checks
     * that what it publishes matches this file; here, the file itself is fed in and
     * must confirm the booking. A rename on either side breaks one of the two.
     */
    @Test
    void the_shared_contract_confirms_the_hold() throws Exception {
        book("A7X2", 5512);   // the booking the contract file names

        publish("5512|A7X2", Files.readString(Path.of("../contracts/payment-event.json")));

        Booking b = awaitStatus("A7X2", 5512, BookingStatus.CONFIRMED);
        assertThat(seatRepo.findById(b.getSeatId()).orElseThrow().getStatus()).isEqualTo(SeatStatus.CONFIRMED);
    }

    @Test
    void paying_for_a_waitlist_hold_keeps_the_place() {
        book("A1", 1);
        book("A2", 2);                                   // both berths
        book("W", 3);                                    // waitlist

        publish("3|W", paid(3, "W"));

        assertThat(awaitStatus("W", 3, BookingStatus.WAITLISTED).getWaitlistPos()).isEqualTo(1);
    }

    /** payment-service announces at least once, so a repeat must do nothing. */
    @Test
    void the_same_paid_event_twice_is_harmless() {
        book("A7X2", 5512);
        publish("5512|A7X2", paid(5512, "A7X2"));
        awaitStatus("A7X2", 5512, BookingStatus.CONFIRMED);

        publish("5512|A7X2", paid(5512, "A7X2"));
        book("NEXT", 99);
        publish("99|NEXT", paid(99, "NEXT"));            // proves the duplicate was consumed
        awaitStatus("NEXT", 99, BookingStatus.CONFIRMED);

        assertThat(bookingRepo.findByUserIdAndRequestId(5512L, "A7X2").orElseThrow().getStatus())
                .isEqualTo(BookingStatus.CONFIRMED);
    }

    /**
     * Money that arrives after the deadline is refunded instead of confirmed, and
     * the payments behind it carry on. What happens to the refund itself is
     * LatePaymentTest's job; this one is about the queue not getting stuck.
     *
     * Note what this does NOT prove: if apply() threw instead, this test would still
     * pass. Spring Kafka's default error handler retries a failing message a few
     * times, then skips it — so the queue never blocks forever either way. The
     * difference is what happens to the message: handled here, versus retried and
     * then DROPPED there. A dropped late payment is a refund that never happens.
     */
    @Test
    void a_late_payment_is_recognised_and_the_next_one_still_goes_through() {
        book("LATE", 1);
        Instant tooLate = bookingRepo.findByUserIdAndRequestId(1L, "LATE").orElseThrow()
                .getPayBy().plus(Duration.ofMinutes(1));
        expiryJob.releaseExpired(Instant.now().plus(Duration.ofMinutes(10)));
        assertThat(bookingRepo.findByUserIdAndRequestId(1L, "LATE").orElseThrow().getStatus())
                .isEqualTo(BookingStatus.EXPIRED);
        book("ONTIME", 2);

        publish("1|LATE", paidAt(1, "LATE", tooLate));
        publish("2|ONTIME", paid(2, "ONTIME"));

        awaitStatus("ONTIME", 2, BookingStatus.CONFIRMED);
        assertThat(bookingRepo.findByUserIdAndRequestId(1L, "LATE").orElseThrow().getStatus())
                .as("still expired — the money goes back instead").isEqualTo(BookingStatus.EXPIRED);
    }

    // ---------- helpers ----------

    private void book(String requestId, long userId) {
        bookingService.book(new BookingCommand(requestId, userId, "12951", DATE, "3A", TestPassenger.SOMEONE));
    }

    private String paid(long userId, String requestId) {
        return paidAt(userId, requestId, Instant.now());
    }

    private String paidAt(long userId, String requestId, Instant paidAt) {
        return """
                {"type":"PAID","userId":%d,"requestId":"%s","orderId":"order_x","paymentId":"pay_x",\
                "amountPaise":240000,"paidAt":"%s"}""".formatted(userId, requestId, paidAt);
    }

    private void publish(String key, String json) {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        try (Producer<String, String> producer = new KafkaProducer<>(props)) {
            producer.send(new ProducerRecord<>("payment-events", key, json));
            producer.flush();
        }
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
