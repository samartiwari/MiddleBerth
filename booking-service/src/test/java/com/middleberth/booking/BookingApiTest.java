package com.middleberth.booking;

import com.middleberth.booking.domain.Seat;
import com.middleberth.booking.domain.SeatStatus;
import com.middleberth.booking.domain.Train;
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
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 2, end to end: real HTTP, real Kafka, real Postgres.
 *
 * The API is asynchronous now. POST returns 202 and a request id in a couple of
 * milliseconds; the answer appears later, and the page polls for it.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BookingApiTest {

    private static final int SEATS = 24;
    private static final int PEOPLE = 500;
    private static final LocalDate DATE = LocalDate.of(2026, 8, 25);

    @Autowired TestRestTemplate http;
    @Autowired SeatRepository seatRepo;
    @Autowired TrainRepository trainRepo;
    @Autowired BookingRepository bookingRepo;
    @Autowired TransactionTemplate tx;

    @BeforeEach
    void seed() {
        tx.executeWithoutResult(s -> {
            bookingRepo.deleteAllInBatch();
            seatRepo.deleteAllInBatch();
            trainRepo.deleteAllInBatch();
            Train train = trainRepo.save(new Train("12951", "Mumbai Rajdhani"));
            for (int n = 1; n <= SEATS; n++) {
                seatRepo.save(new Seat(train.getId(), DATE, "3A", "B2",
                        String.valueOf(n), SeatStatus.FREE));
            }
        });
    }

    private ResponseEntity<String> post(String requestId, long userId, String trainNumber) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String json = """
                {"requestId":"%s","userId":%d,"trainNumber":"%s",
                 "travelDate":"2026-08-25","coachClass":"3A"}
                """.formatted(requestId, userId, trainNumber);
        return http.postForEntity("/api/bookings", new HttpEntity<>(json, headers), String.class);
    }

    /** Polls like the real page would, until the answer stops being PENDING. */
    private String awaitOutcome(String requestId, long userId, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        String body = null;
        while (System.nanoTime() < deadline) {
            body = http.getForObject("/api/bookings/{id}?userId={u}", String.class, requestId, userId);
            if (body != null && !body.contains("PENDING")) return body;
            try { Thread.sleep(20); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
        }
        return body;
    }

    @Test
    void post_is_accepted_immediately_and_the_answer_arrives_later() {
        ResponseEntity<String> accepted = post("A7X2", 5512L, "12951");

        assertThat(accepted.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(accepted.getBody()).contains("\"requestId\":\"A7X2\"").contains("PENDING");

        String outcome = awaitOutcome("A7X2", 5512L, Duration.ofSeconds(30));
        assertThat(outcome).contains("\"status\":\"HELD\"").contains("\"seat\":\"B2-");
    }

    @Test
    void unknown_train_is_rejected_at_the_door() {
        ResponseEntity<String> res = post("A7X3", 5512L, "99999");

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(res.getBody()).contains("TRAIN_NOT_FOUND");
    }

    @Test
    void a_request_missing_fields_is_400() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> res = http.postForEntity("/api/bookings", new HttpEntity<>("""
                {"requestId":"","userId":-1,"trainNumber":"12951",
                 "travelDate":"2026-08-25","coachClass":"3A"}
                """, headers), String.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody()).contains("INVALID_REQUEST").contains("requestId").contains("userId");
    }

    @Test
    void five_hundred_requests_through_kafka_never_double_book() throws Exception {
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch posted = new CountDownLatch(PEOPLE);
        Queue<Throwable> failures = new ConcurrentLinkedQueue<>();
        ExecutorService pool = Executors.newFixedThreadPool(100);

        for (int i = 0; i < PEOPLE; i++) {
            int n = i;
            pool.submit(() -> {
                try {
                    go.await();
                    ResponseEntity<String> r = post("REQ-" + n, 1000L + n, "12951");
                    if (r.getStatusCode() != HttpStatus.ACCEPTED) {
                        failures.add(new AssertionError("expected 202, got " + r.getStatusCode()));
                    }
                } catch (Throwable t) {
                    failures.add(t);
                } finally {
                    posted.countDown();
                }
            });
        }

        long start = System.nanoTime();
        go.countDown();
        assertThat(posted.await(120, TimeUnit.SECONDS)).as("all accepted").isTrue();
        long acceptedMs = (System.nanoTime() - start) / 1_000_000;
        assertThat(failures).as("every POST returned 202").isEmpty();

        // now wait for the consumer to work through the queue
        List<String> outcomes = new ArrayList<>();
        for (int i = 0; i < PEOPLE; i++) {
            outcomes.add(awaitOutcome("REQ-" + i, 1000L + i, Duration.ofSeconds(60)));
        }
        long totalMs = (System.nanoTime() - start) / 1_000_000;
        pool.shutdownNow();

        List<String> held = outcomes.stream().filter(o -> o.contains("\"HELD\"")).toList();
        List<String> waitlisted = outcomes.stream().filter(o -> o.contains("\"WAITLISTED\"")).toList();

        System.out.printf("%n%d POSTs accepted in %d ms; all processed by %d ms — %d HELD, %d WAITLISTED%n",
                PEOPLE, acceptedMs, totalMs, held.size(), waitlisted.size());

        assertThat(held).hasSize(SEATS);
        assertThat(waitlisted).hasSize(PEOPLE - SEATS);
        assertThat(new HashSet<>(held)).as("distinct berths").hasSize(SEATS);

        assertThat(bookingRepo.count()).isEqualTo(PEOPLE);
        assertThat(seatRepo.findAll()).allMatch(s -> s.getStatus() == SeatStatus.HELD);

        List<Integer> positions = waitlisted.stream()
                .map(o -> Integer.parseInt(o.replaceAll(".*\"position\":(\\d+).*", "$1")))
                .toList();
        long distinct = positions.stream().distinct().count();
        System.out.printf("waitlist: %d people, %d distinct positions, %d duplicates%n",
                positions.size(), distinct, positions.size() - distinct);
    }
}
