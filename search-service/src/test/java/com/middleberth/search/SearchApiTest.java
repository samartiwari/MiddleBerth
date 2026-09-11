package com.middleberth.search;

import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.kafka.KafkaConnectionDetails;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Duration;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 3 end to end: a seat-count event from booking-service turns into an
 * availability answer, with Redis in between and Postgres for the train name.
 *
 * Nothing here touches booking-service. That is the point of the service.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SearchApiTest {

    private static final String DATE = "2026-08-25";

    @Autowired TestRestTemplate http;
    @Autowired StringRedisTemplate redis;
    @Autowired KafkaConnectionDetails kafka;

    @BeforeEach
    void clearCounts() {
        Set<String> keys = redis.keys("seats:*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    @Test
    void lists_the_trains_it_knows_about() {
        String body = http.getForObject("/api/trains", String.class);

        assertThat(body).contains("12951").contains("Mumbai Rajdhani")
                        .contains("12009").contains("22691");
    }

    @Test
    void a_train_with_no_seat_count_yet_is_unknown() {
        String body = availability("12951", DATE, "3A");

        assertThat(body).contains("\"status\":\"UNKNOWN\"");
        assertThat(body).doesNotContain("freeSeats");   // NON_NULL leaves it out
        assertThat(body).contains("Mumbai Rajdhani");   // name still comes from Postgres
    }

    @Test
    void an_event_from_booking_service_becomes_an_availability_answer() {
        publishSeatCount("12951", DATE, "3A", 23);

        String body = awaitStatus("12951", DATE, "3A", "AVAILABLE", Duration.ofSeconds(20));

        assertThat(body).contains("\"status\":\"AVAILABLE\"")
                        .contains("\"freeSeats\":23")
                        .contains("Mumbai Rajdhani");
    }

    @Test
    void the_newest_event_wins() {
        publishSeatCount("12009", DATE, "SL", 40);
        awaitStatus("12009", DATE, "SL", "AVAILABLE", Duration.ofSeconds(20));

        publishSeatCount("12009", DATE, "SL", 7);

        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        String body = null;
        while (System.nanoTime() < deadline) {
            body = availability("12009", DATE, "SL");
            if (body.contains("\"freeSeats\":7")) break;
            sleep(50);
        }
        assertThat(body).contains("\"freeSeats\":7");
    }

    @Test
    void zero_free_berths_reads_as_waitlist() {
        publishSeatCount("22691", DATE, "2A", 0);

        String body = awaitStatus("22691", DATE, "2A", "WAITLIST", Duration.ofSeconds(20));

        assertThat(body).contains("\"status\":\"WAITLIST\"");
        assertThat(body).doesNotContain("freeSeats");
    }

    @Test
    void an_unknown_train_is_404() {
        ResponseEntity<String> res = http.getForEntity(
                "/api/trains/99999/availability?date={d}&class=3A", String.class, DATE);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(res.getBody()).contains("TRAIN_NOT_FOUND");
    }

    // ---------- helpers ----------

    private String availability(String train, String date, String coachClass) {
        return http.getForObject("/api/trains/{t}/availability?date={d}&class={c}",
                String.class, train, date, coachClass);
    }

    private String awaitStatus(String train, String date, String coachClass,
                               String expected, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        String body = null;
        while (System.nanoTime() < deadline) {
            body = availability(train, date, coachClass);
            if (body != null && body.contains("\"status\":\"" + expected + "\"")) return body;
            sleep(50);
        }
        return body;
    }

    /** Stands in for booking-service. A raw producer, because search-service produces nothing. */
    private void publishSeatCount(String train, String date, String coachClass, int freeSeats) {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);

        String json = """
                {"trainNumber":"%s","travelDate":"%s","coachClass":"%s","freeSeats":%d}
                """.formatted(train, date, coachClass, freeSeats);

        try (Producer<String, String> producer = new KafkaProducer<>(props)) {
            producer.send(new ProducerRecord<>("seat-counts",
                    train + "|" + date + "|" + coachClass, json));
            producer.flush();
        }
    }

    private void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
