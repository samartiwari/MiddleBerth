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
import com.middleberth.booking.service.HoldExpiryJob;
import com.middleberth.booking.service.SeatCountSnapshotJob;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
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
    @Autowired HoldExpiryJob expiryJob;
    @Autowired SeatRepository seatRepo;
    @Autowired TrainRepository trainRepo;
    @Autowired BookingRepository bookingRepo;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;
    @Autowired KafkaConnectionDetails kafka;
    @Autowired SeatCountSnapshotJob snapshotJob;

    @BeforeEach
    void seed() {
        tx.executeWithoutResult(s -> {
            TestDatabase.wipe(jdbc);
            Train train = trainRepo.save(new Train("12951", "Mumbai Rajdhani"));
            for (int n = 1; n <= 24; n++) {
                seatRepo.save(new Seat(train.getId(), DATE, "3A", "B2",
                        String.valueOf(n), SeatStatus.FREE));
            }
        });
    }

    @Test
    void claiming_a_berth_publishes_the_remaining_count() {
        try (Consumer<String, String> consumer = consumerFromNow()) {

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

        try (Consumer<String, String> consumer = consumerFromNow()) {
            bookingService.book(new BookingCommand("LATE", 9999L, "12951", DATE, "3A"));

            List<ConsumerRecord<String, String>> records = drain(consumer, 1, Duration.ofSeconds(5));
            assertThat(records).as("a waitlisted booking touches no seat rows").isEmpty();
        }
    }

    /**
     * When nobody pays for a berth and nobody is waiting, it goes back on sale —
     * and search-service must hear the count go UP, or it keeps showing the berth
     * as taken.
     */
    @Test
    void an_expired_berth_going_back_on_sale_publishes_the_higher_count() {
        bookingService.book(new BookingCommand("GONE", 5512L, "12951", DATE, "3A"));   // 23 free

        try (Consumer<String, String> consumer = consumerFromNow()) {
            expiryJob.releaseExpired(Instant.now().plus(Duration.ofMinutes(10)));

            List<ConsumerRecord<String, String>> records = drain(consumer, 1, Duration.ofSeconds(20));
            assertThat(records).as("the release was announced").hasSize(1);
            assertThat(freeSeatsIn(records.get(0).value())).as("back up to 24").isEqualTo(24);
        }
    }

    /**
     * The other half of this lives in search-service: it reads the same file as
     * its input. So the two services are held to one shape by two tests, and a
     * rename on either side breaks one of them.
     *
     * Checks that every field search-service relies on is present with the same
     * JSON type. Extra fields are allowed — consumers ignore what they do not
     * know, so adding one is not a breaking change. Removing or renaming one is.
     */
    @Test
    void what_it_publishes_matches_the_shared_contract() throws Exception {
        JsonNode contract = new ObjectMapper().readTree(
                Path.of("../contracts/seat-count-event.json").toFile());

        try (Consumer<String, String> consumer = consumerFromNow()) {
            bookingService.book(new BookingCommand("CONTRACT", 4242L, "12951", DATE, "3A"));

            List<ConsumerRecord<String, String>> records = drain(consumer, 1, Duration.ofSeconds(20));
            assertThat(records).as("an event was published").isNotEmpty();

            JsonNode published = new ObjectMapper().readTree(records.get(0).value());

            contract.fieldNames().forEachRemaining(field -> {
                assertThat(published.has(field))
                        .as("booking-service publishes '%s', which search-service reads", field)
                        .isTrue();
                assertThat(published.get(field).getNodeType())
                        .as("'%s' has the type search-service expects", field)
                        .isEqualTo(contract.get(field).getNodeType());
            });
        }
    }

    /**
     * The cold start: search-service answers from Redis and nothing else, so an
     * empty Redis used to mean UNKNOWN until somebody happened to book that train.
     * The snapshot fills it in, and repairs any count that went missing.
     *
     * Seeded on a FUTURE date on purpose: the snapshot ignores dates that have
     * already been travelled, and this class's other tests use a date in the past.
     */
    @Test
    void the_snapshot_publishes_every_count_from_scratch() {
        LocalDate soon = LocalDate.now().plusDays(1);
        tx.executeWithoutResult(t -> {
            Long trainId = trainRepo.findByNumber("12951").orElseThrow().getId();
            for (int n = 1; n <= 2; n++) {
                seatRepo.save(new Seat(trainId, soon, "SL", "S4", String.valueOf(n), SeatStatus.FREE));
            }
        });

        try (Consumer<String, String> consumer = consumerFromNow()) {
            int published = snapshotJob.publishSnapshot();

            assertThat(published).as("one line per train, date and class still to travel").isEqualTo(1);
            List<ConsumerRecord<String, String>> records = drain(consumer, 1, Duration.ofSeconds(20));
            assertThat(records).hasSize(1);
            assertThat(freeSeatsIn(records.get(0).value())).isEqualTo(2);
            assertThat(records.get(0).key()).isEqualTo("12951|" + soon + "|SL");
        }
    }

    /**
     * A payment that arrived in time but reached us after the hold was released is
     * given a free berth — which takes it out of circulation. Nothing used to say
     * so, and search claimed it was still free until the next booking.
     */
    @Test
    void a_late_payment_that_takes_a_berth_publishes_the_new_count() {
        bookingService.book(new BookingCommand("LATE", 42L, "12951", DATE, "3A"));
        Instant inTime = bookingRepo.findByUserIdAndRequestId(42L, "LATE").orElseThrow()
                .getPayBy().minusSeconds(2);
        expiryJob.releaseExpired(Instant.now().plus(Duration.ofMinutes(30)));   // 24 free again

        try (Consumer<String, String> consumer = consumerFromNow()) {
            publishPaid(42L, "LATE", inTime);

            List<ConsumerRecord<String, String>> records = drain(consumer, 1, Duration.ofSeconds(30));
            assertThat(records).as("the honoured berth was announced").hasSize(1);
            assertThat(freeSeatsIn(records.get(0).value())).isEqualTo(23);
        }
    }

    /** Stands in for payment-service. */
    private void publishPaid(long userId, String requestId, Instant paidAt) {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        String body = ("{\"type\":\"PAID\",\"userId\":%d,\"requestId\":\"%s\","
                + "\"orderId\":\"order_x\",\"paymentId\":\"pay_x\","
                + "\"amountPaise\":240000,\"paidAt\":\"%s\"}").formatted(userId, requestId, paidAt);
        try (Producer<String, String> producer = new KafkaProducer<>(props)) {
            producer.send(new ProducerRecord<>("payment-events", userId + "|" + requestId, body));
            producer.flush();
        }
    }

    // ---------- helpers ----------

    /**
     * A consumer positioned at the END of every partition, so each test sees only
     * the events it publishes itself.
     *
     * Reading from the start was a latent bug: the topic lives for the whole test
     * run, so a test would pick up events other tests had left behind, and pass
     * or fail depending on the order JUnit happened to run them in.
     *
     * assign() + seekToEnd() rather than subscribe(), so there is no consumer
     * group rebalance to wait for — the position is fixed before anything is sent.
     */
    private Consumer<String, String> consumerFromNow() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        Consumer<String, String> consumer = new KafkaConsumer<>(props);

        List<TopicPartition> partitions = consumer.partitionsFor(KafkaTopicConfig.SEAT_COUNTS).stream()
                .map(p -> new TopicPartition(p.topic(), p.partition()))
                .toList();
        consumer.assign(partitions);
        consumer.seekToEnd(partitions);
        partitions.forEach(consumer::position);   // resolve the lazy seek now, before we publish
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
