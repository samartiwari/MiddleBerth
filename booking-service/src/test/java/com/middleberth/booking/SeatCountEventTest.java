package com.middleberth.booking;

import com.middleberth.booking.domain.Seat;
import com.middleberth.booking.domain.SeatStatus;
import com.middleberth.booking.domain.Train;
import com.middleberth.booking.dto.BookingCommand;
import com.middleberth.booking.kafka.KafkaTopicConfig;
import com.middleberth.booking.repository.BookingRepository;
import com.middleberth.booking.repository.SeatRepository;
import com.middleberth.booking.repository.TrainRepository;
import com.middleberth.booking.service.BookingService;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.kafka.KafkaConnectionDetails;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDate;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves booking-service tells search-service how many berths are left.
 *
 * Reads the topic with a plain consumer rather than a @KafkaListener, so this
 * test shares the existing Spring context instead of forcing a third one (and
 * two more containers).
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class SeatCountEventTest {

    private static final LocalDate DATE = LocalDate.of(2026, 8, 25);

    @Autowired BookingService bookingService;
    @Autowired SeatRepository seatRepo;
    @Autowired TrainRepository trainRepo;
    @Autowired BookingRepository bookingRepo;
    @Autowired TransactionTemplate tx;
    @Autowired KafkaConnectionDetails kafka;

    @BeforeEach
    void seed() {
        tx.executeWithoutResult(s -> {
            bookingRepo.deleteAllInBatch();
            seatRepo.deleteAllInBatch();
            trainRepo.deleteAllInBatch();
            Train train = trainRepo.save(new Train("12951", "Mumbai Rajdhani"));
            for (int n = 1; n <= 24; n++) {
                seatRepo.save(new Seat(train.getId(), DATE, "3A", "B2",
                        String.valueOf(n), SeatStatus.FREE));
            }
        });
    }

    @Test
    void claiming_a_berth_publishes_the_remaining_count() {
        try (Consumer<String, String> consumer = consumerFromTheStart()) {

            bookingService.book(new BookingCommand("A7X2", 5512L, "12951", DATE, "3A"));
            bookingService.book(new BookingCommand("B9K4", 7731L, "12951", DATE, "3A"));
            bookingService.book(new BookingCommand("C1M7", 9910L, "12951", DATE, "3A"));

            List<ConsumerRecord<String, String>> records = drain(consumer, 3, Duration.ofSeconds(20));

            assertThat(records).as("one event per claimed berth").hasSize(3);

            // absolute counts, counting down as berths go
            assertThat(records).extracting(r -> freeSeatsIn(r.value()))
                    .containsExactly(23, 22, 21);

            // same key every time, so the order above is guaranteed
            assertThat(records).extracting(ConsumerRecord::key)
                    .containsOnly("12951|2026-08-25|3A");
        }
    }

    @Test
    void waitlisting_publishes_nothing() {
        // take all 24 berths first
        for (int i = 0; i < 24; i++) {
            bookingService.book(new BookingCommand("TAKE-" + i, 1000L + i, "12951", DATE, "3A"));
        }

        try (Consumer<String, String> consumer = consumerFromTheEnd()) {
            bookingService.book(new BookingCommand("LATE", 9999L, "12951", DATE, "3A"));

            List<ConsumerRecord<String, String>> records = drain(consumer, 1, Duration.ofSeconds(5));
            assertThat(records).as("a waitlisted booking touches no seat rows").isEmpty();
        }
    }

    // ---------- helpers ----------

    private Consumer<String, String> consumerFromTheStart() {
        return newConsumer("earliest");
    }

    private Consumer<String, String> consumerFromTheEnd() {
        Consumer<String, String> c = newConsumer("latest");
        c.poll(Duration.ofMillis(500));   // force assignment so "latest" means "from now"
        return c;
    }

    private Consumer<String, String> newConsumer(String offsetReset) {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "seat-count-test-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, offsetReset);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        Consumer<String, String> consumer = new KafkaConsumer<>(props);
        consumer.subscribe(List.of(KafkaTopicConfig.SEAT_COUNTS));
        return consumer;
    }

    private List<ConsumerRecord<String, String>> drain(Consumer<String, String> consumer,
                                                       int expected, Duration timeout) {
        List<ConsumerRecord<String, String>> out = new ArrayList<>();
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline && out.size() < expected) {
            consumer.poll(Duration.ofMillis(200)).forEach(out::add);
        }
        return out;
    }

    private int freeSeatsIn(String json) {
        return Integer.parseInt(json.replaceAll(".*\"freeSeats\"\\s*:\\s*(\\d+).*", "$1"));
    }
}
