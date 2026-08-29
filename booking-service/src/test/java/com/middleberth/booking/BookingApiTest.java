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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

/** Phase 1, end to end: real HTTP, real Tomcat, real Postgres. */
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

    private String body(String requestId, long userId, String trainNumber, String coachClass) {
        return """
               {"requestId":"%s","userId":%d,"trainNumber":"%s",
                "travelDate":"2026-08-25","coachClass":"%s"}
               """.formatted(requestId, userId, trainNumber, coachClass);
    }

    private ResponseEntity<String> post(String json) {
        var headers = new org.springframework.http.HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        return http.postForEntity("/api/bookings",
                new org.springframework.http.HttpEntity<>(json, headers), String.class);
    }

    @Test
    void booking_a_free_berth_returns_held_and_the_seat() {
        ResponseEntity<String> res = post(body("A7X2", 5512L, "12951", "3A"));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).contains("\"status\":\"HELD\"").contains("\"seat\":\"B2-");
        assertThat(res.getBody()).doesNotContain("position");   // NON_NULL leaves it out
    }

    @Test
    void unknown_train_is_404() {
        ResponseEntity<String> res = post(body("A7X3", 5512L, "99999", "3A"));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(res.getBody()).contains("TRAIN_NOT_FOUND");
    }

    @Test
    void a_request_missing_fields_is_400() {
        ResponseEntity<String> res = post("""
                {"requestId":"","userId":-1,"trainNumber":"12951",
                 "travelDate":"2026-08-25","coachClass":"3A"}
                """);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody()).contains("INVALID_REQUEST")
                .contains("requestId").contains("userId");
    }

    @Test
    void five_hundred_http_requests_never_double_book() throws Exception {
        Queue<String> bodies = new ConcurrentLinkedQueue<>();
        Queue<Throwable> failures = new ConcurrentLinkedQueue<>();
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(PEOPLE);
        ExecutorService pool = Executors.newFixedThreadPool(PEOPLE);

        for (int i = 0; i < PEOPLE; i++) {
            int n = i;
            pool.submit(() -> {
                try {
                    go.await();
                    bodies.add(post(body("REQ-" + n, 1000L + n, "12951", "3A")).getBody());
                } catch (Throwable t) {
                    failures.add(t);
                } finally {
                    done.countDown();
                }
            });
        }

        long start = System.nanoTime();
        go.countDown();
        boolean finished = done.await(180, TimeUnit.SECONDS);
        long ms = (System.nanoTime() - start) / 1_000_000;
        pool.shutdownNow();

        assertThat(finished).as("all requests returned").isTrue();
        assertThat(failures).as("no request blew up").isEmpty();

        List<String> held = bodies.stream().filter(b -> b.contains("\"HELD\"")).toList();
        List<String> waitlisted = bodies.stream().filter(b -> b.contains("\"WAITLISTED\"")).toList();

        System.out.printf("%n%d HTTP requests in %d ms — %d HELD, %d WAITLISTED%n",
                PEOPLE, ms, held.size(), waitlisted.size());

        assertThat(held).hasSize(SEATS);
        assertThat(waitlisted).hasSize(PEOPLE - SEATS);

        Set<String> berths = new HashSet<>(held);          // whole body differs only by seat
        assertThat(berths).as("distinct berths").hasSize(SEATS);

        assertThat(bookingRepo.count()).isEqualTo(PEOPLE);
        assertThat(seatRepo.findAll()).allMatch(s -> s.getStatus() == SeatStatus.HELD);
    }
}
