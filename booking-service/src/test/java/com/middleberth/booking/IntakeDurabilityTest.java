package com.middleberth.booking;

import com.middleberth.booking.domain.BookingStatus;
import com.middleberth.booking.domain.Seat;
import com.middleberth.booking.domain.SeatStatus;
import com.middleberth.booking.domain.Train;
import com.middleberth.booking.kafka.KafkaTopicConfig;
import com.middleberth.booking.repository.BookingRepository;
import com.middleberth.booking.repository.SeatRepository;
import com.middleberth.booking.repository.TrainRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDate;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;

/**
 * The last place this system could lose someone's work.
 *
 * Intake used to hand the request to the Kafka producer and reply 202 without
 * waiting. If that send failed, the user walked away with a request id for a
 * booking that was never going to happen — no error, no row, no message, nobody
 * any the wiser.
 *
 * Now the front door waits for the broker. If Kafka will not take it, the answer
 * is 503, which is both honest and something a client can act on: retry with the
 * same request id.
 *
 * Its own class because spying on the Kafka template changes the Spring context.
 * Worth a set of containers for the one path where work can vanish.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class IntakeDurabilityTest {

    private static final LocalDate DATE = LocalDate.of(2026, 8, 25);

    @Autowired TestRestTemplate http;
    @Autowired BookingRepository bookingRepo;
    @Autowired SeatRepository seatRepo;
    @Autowired TrainRepository trainRepo;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;

    // The raw type on purpose: Spring Boot declares this bean as
    // KafkaTemplate<?, ?>, and a spy has to match the bean's declared type or
    // there is "no bean to wrap".
    @SuppressWarnings("rawtypes")
    @MockitoSpyBean KafkaTemplate kafka;

    @BeforeEach
    void seed() {
        TestDatabase.wipe(jdbc);
        tx.executeWithoutResult(s -> {
            Train train = trainRepo.save(new Train("12951", "Mumbai Rajdhani"));
            seatRepo.save(new Seat(train.getId(), DATE, "3A", "B2", "31", SeatStatus.FREE));
        });
    }

    @Test
    @SuppressWarnings("unchecked")
    void a_request_the_queue_will_not_take_is_refused_and_then_survives_a_retry() {
        // Kafka is unreachable for the first attempt, fine for the second
        doReturn(CompletableFuture.failedFuture(new IllegalStateException("broker unreachable")))
                .doCallRealMethod()
                .when(kafka).send(eq(KafkaTopicConfig.BOOKING_REQUESTS), anyString(), any());

        ResponseEntity<String> refused = book("A7X2", 5512);

        assertThat(refused.getStatusCode())
                .as("503, not 202 — the request was never queued").isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(refused.getBody()).contains("QUEUE_UNAVAILABLE");
        assertThat(stayedEmpty("A7X2", 5512)).as("and nothing was booked").isTrue();

        // The client tries again with the SAME request id, which is the whole point
        ResponseEntity<String> accepted = book("A7X2", 5512);

        assertThat(accepted.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(awaitBooked("A7X2", 5512)).isEqualTo(BookingStatus.HELD);
        assertThat(bookingRepo.count()).as("one booking, not two").isEqualTo(1);
    }

    // ---------- helpers ----------

    private ResponseEntity<String> book(String requestId, long userId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-User-Id", String.valueOf(userId));
        String body = """
                {"requestId":"%s","trainNumber":"12951","travelDate":"%s","coachClass":"3A",\
                 "passenger":{"name":"Test Passenger","email":"passenger@example.invalid","phone":"9876543210"}}"""
                .formatted(requestId, DATE);
        return http.postForEntity("/api/bookings", new HttpEntity<>(body, headers), String.class);
    }

    /** Give the consumer every chance to invent a booking out of nothing. */
    private boolean stayedEmpty(String requestId, long userId) {
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (System.nanoTime() < deadline) {
            if (bookingRepo.findByUserIdAndRequestId(userId, requestId).isPresent()) {
                return false;
            }
            sleep();
        }
        return true;
    }

    private BookingStatus awaitBooked(String requestId, long userId) {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < deadline) {
            var booking = bookingRepo.findByUserIdAndRequestId(userId, requestId);
            if (booking.isPresent()) {
                return booking.get().getStatus();
            }
            sleep();
        }
        throw new AssertionError("the retry was accepted but never became a booking");
    }

    private void sleep() {
        try {
            Thread.sleep(50);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
