package com.middleberth.booking;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.middleberth.booking.domain.BookingStatus;
import com.middleberth.booking.domain.Seat;
import com.middleberth.booking.domain.SeatStatus;
import com.middleberth.booking.domain.Train;
import com.middleberth.booking.dto.BookingCommand;
import com.middleberth.booking.repository.BookingRepository;
import com.middleberth.booking.repository.SeatRepository;
import com.middleberth.booking.repository.TrainRepository;
import com.middleberth.booking.service.BookingPayments;
import com.middleberth.booking.service.BookingService;
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
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Giving a ticket up.
 *
 * The interesting part is not the cancelling — it is where the berth goes. It
 * does NOT go back on sale: it goes straight to the next paid waitlister inside
 * the same transaction, exactly as when a hold expires. Anything else lets
 * somebody refreshing the search page jump a queue of people who have paid.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CancellationTest {

    private static final LocalDate DATE = LocalDate.of(2026, 8, 25);

    @Autowired TestRestTemplate http;
    @Autowired BookingService bookingService;
    @Autowired BookingPayments payments;
    @Autowired BookingRepository bookingRepo;
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

    /** A paid ticket given up: the berth is freed and the money goes back. */
    @Test
    void cancelling_a_confirmed_ticket_frees_the_berth_and_asks_for_a_refund() throws Exception {
        book("A7X2", 5512);
        payments.apply(5512L, "A7X2", Instant.now());
        Long berth = bookingRepo.findByUserIdAndRequestId(5512L, "A7X2").orElseThrow().getSeatId();

        try (Consumer<String, String> refunds = refundRequests()) {
            ResponseEntity<String> res = cancel("A7X2", 5512);

            assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(res.getBody()).contains("\"refundOnItsWay\":true").contains("\"pnr\":");

            JsonNode refund = json.readTree(awaitOne(refunds).value());
            assertThat(refund.get("reason").asText()).isEqualTo("CANCELLED_BY_PASSENGER");
            assertThat(refund.get("requestId").asText()).isEqualTo("A7X2");
        }

        assertThat(status("A7X2", 5512)).isEqualTo(BookingStatus.CANCELLED);
        assertThat(seatRepo.findById(berth).orElseThrow().getStatus())
                .as("nobody was waiting, so it goes back on sale").isEqualTo(SeatStatus.FREE);
    }

    /**
     * The one that matters. A cancelled berth must never pass through FREE while
     * somebody who has already paid is waiting for it.
     */
    @Test
    void a_cancelled_berth_goes_to_the_next_paid_waitlister() {
        book("HOLDER", 1);                                  // takes the only berth
        book("WAITER", 2);                                  // waitlisted
        payments.apply(1L, "HOLDER", Instant.now());
        payments.apply(2L, "WAITER", Instant.now());        // paid, waiting
        Long berth = bookingRepo.findByUserIdAndRequestId(1L, "HOLDER").orElseThrow().getSeatId();

        cancel("HOLDER", 1);

        var waiter = bookingRepo.findByUserIdAndRequestId(2L, "WAITER").orElseThrow();
        assertThat(waiter.getStatus()).as("promoted, not left waiting").isEqualTo(BookingStatus.CONFIRMED);
        assertThat(waiter.getSeatId()).isEqualTo(berth);
        assertThat(seatRepo.findById(berth).orElseThrow().getStatus())
                .as("never FREE, not for a moment").isEqualTo(SeatStatus.CONFIRMED);
    }

    /** An unpaid hold owes nobody anything. */
    @Test
    void cancelling_an_unpaid_hold_asks_for_no_refund() {
        book("A7X2", 5512);

        ResponseEntity<String> res = cancel("A7X2", 5512);

        assertThat(res.getBody()).contains("\"refundOnItsWay\":false");
        assertThat(status("A7X2", 5512)).isEqualTo(BookingStatus.CANCELLED);
    }

    @Test
    void a_waitlisted_ticket_gives_its_place_back() {
        book("HOLDER", 1);
        book("WAITER", 2);
        payments.apply(2L, "WAITER", Instant.now());

        cancel("WAITER", 2);

        assertThat(status("WAITER", 2)).isEqualTo(BookingStatus.CANCELLED);
        // The slot is free again, so the next person is not regretted.
        book("LATECOMER", 3);
        assertThat(status("LATECOMER", 3)).isEqualTo(BookingStatus.WAITLIST_HELD);
    }

    @Test
    void nobody_can_cancel_somebody_elses_ticket() {
        book("A7X2", 5512);

        assertThat(cancel("A7X2", 7731).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(status("A7X2", 5512)).as("untouched").isEqualTo(BookingStatus.HELD);
    }

    @Test
    void a_ticket_cannot_be_cancelled_twice() {
        book("A7X2", 5512);
        payments.apply(5512L, "A7X2", Instant.now());
        cancel("A7X2", 5512);

        ResponseEntity<String> again = cancel("A7X2", 5512);

        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(again.getBody()).contains("NOT_CANCELLABLE");
    }

    // ---------- helpers ----------

    private void book(String requestId, long userId) {
        bookingService.book(new BookingCommand(requestId, userId, "12951", DATE, "3A", TestPassenger.SOMEONE));
    }

    private ResponseEntity<String> cancel(String requestId, long userId) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-User-Id", String.valueOf(userId));
        return http.exchange("/api/bookings/" + requestId + "/cancel",
                HttpMethod.POST, new HttpEntity<>(headers), String.class);
    }

    private BookingStatus status(String requestId, long userId) {
        return bookingRepo.findByUserIdAndRequestId(userId, requestId).orElseThrow().getStatus();
    }

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

    private ConsumerRecord<String, String> awaitOne(Consumer<String, String> consumer) {
        List<ConsumerRecord<String, String>> out = new ArrayList<>();
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < deadline && out.isEmpty()) {
            consumer.poll(Duration.ofMillis(200)).forEach(out::add);
        }
        assertThat(out).as("a refund was asked for").hasSize(1);
        return out.get(0);
    }
}
