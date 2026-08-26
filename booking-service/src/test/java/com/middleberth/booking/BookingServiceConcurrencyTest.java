package com.middleberth.booking;

import com.middleberth.booking.domain.*;
import com.middleberth.booking.repository.BookingRepository;
import com.middleberth.booking.repository.SeatRepository;
import com.middleberth.booking.repository.TrainRepository;
import com.middleberth.booking.dto.BookingCommand;
import com.middleberth.booking.dto.BookingResult;
import com.middleberth.booking.service.BookingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 1's definition of done, through the service rather than the repository.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class BookingServiceConcurrencyTest {

    private static final int SEATS = 24;
    private static final int PEOPLE = 500;
    private static final LocalDate DATE = LocalDate.of(2026, 8, 25);
    private static final String CLASS = "3A";
    private static final String TRAIN = "12951";

    @Autowired BookingService bookingService;
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
            Train train = trainRepo.save(new Train(TRAIN, "Mumbai Rajdhani"));
            for (int n = 1; n <= SEATS; n++) {
                seatRepo.save(new Seat(train.getId(), DATE, CLASS, "B2",
                        String.valueOf(n), SeatStatus.FREE));
            }
        });
    }

    @Test
    void twenty_four_berths_go_to_twenty_four_people_and_the_rest_are_waitlisted() throws Exception {
        List<BookingResult> results = runConcurrently(PEOPLE, i -> new BookingCommand(
                "REQ-" + i, 1000L + i, TRAIN, DATE, CLASS));

        List<BookingResult> held = results.stream()
                .filter(r -> r.status() == BookingStatus.HELD).toList();
        List<BookingResult> waitlisted = results.stream()
                .filter(r -> r.status() == BookingStatus.WAITLISTED).toList();

        assertThat(held).as("berths handed out").hasSize(SEATS);
        assertThat(waitlisted).as("everyone else waitlisted").hasSize(PEOPLE - SEATS);

        // THE assertion — 24 people, 24 different berths
        Set<String> berths = new HashSet<>(held.stream().map(BookingResult::seat).toList());
        assertThat(berths).as("distinct berths").hasSize(SEATS);

        // and the database agrees
        assertThat(bookingRepo.count()).isEqualTo(PEOPLE);
        assertThat(seatRepo.findAll()).allMatch(s -> s.getStatus() == SeatStatus.HELD);

        // how good are the waitlist numbers? (reported, not asserted — see below)
        List<Integer> positions = waitlisted.stream().map(BookingResult::position).toList();
        long distinctPositions = positions.stream().distinct().count();
        System.out.printf("%nwaitlist: %d people, %d distinct positions, %d duplicates%n",
                positions.size(), distinctPositions, positions.size() - distinctPositions);
    }

    //checking if 50 request with same user books 50 tickets or only 1 ticket
    @Test
    void the_same_request_id_only_ever_books_once() throws Exception {
        List<BookingResult> results = runConcurrently(50, i ->
                new BookingCommand("SAME-ID", 5512L, TRAIN, DATE, CLASS));

        assertThat(bookingRepo.count()).as("rows written").isEqualTo(1);

        Set<String> distinctAnswers = new HashSet<>(
                results.stream().map(r -> r.status() + "/" + r.seat() + "/" + r.position()).toList());
        assertThat(distinctAnswers).as("everyone got the same answer").hasSize(1);

        assertThat(seatRepo.findAll().stream()
                .filter(s -> s.getStatus() == SeatStatus.HELD).count())
                .as("only one berth consumed").isEqualTo(1);
    }

    //differt users with same request id should be give 1 seat each(total 2)
    @Test
    void two_users_with_the_same_request_id_each_get_their_own_booking() {
        BookingResult a = bookingService.book(new BookingCommand("A7X2", 5512L, TRAIN, DATE, CLASS));
        BookingResult b = bookingService.book(new BookingCommand("A7X2", 7731L, TRAIN, DATE, CLASS));

        assertThat(bookingRepo.count()).as("both booked").isEqualTo(2);
        assertThat(a.seat()).as("different berths").isNotEqualTo(b.seat());

        // and a retry by either of them is still idempotent
        BookingResult aAgain = bookingService.book(new BookingCommand("A7X2", 5512L, TRAIN, DATE, CLASS));
        assertThat(bookingRepo.count()).as("retry booked nothing new").isEqualTo(2);
        assertThat(aAgain.seat()).isEqualTo(a.seat());
    }

    private List<BookingResult> runConcurrently(int n, java.util.function.IntFunction<BookingCommand> cmd)
            throws Exception {
        Queue<BookingResult> results = new ConcurrentLinkedQueue<>();
        Queue<Throwable> failures = new ConcurrentLinkedQueue<>();
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(n);
        ExecutorService pool = Executors.newFixedThreadPool(n);

        for (int i = 0; i < n; i++) {
            int index = i;
            pool.submit(() -> {
                try {
                    go.await();
                    results.add(bookingService.book(cmd.apply(index)));
                } catch (Throwable t) {
                    failures.add(t);
                } finally {
                    done.countDown();
                }
            });
        }
        go.countDown();
        boolean finished = done.await(120, TimeUnit.SECONDS);
        pool.shutdownNow();

        assertThat(finished).as("all finished").isTrue();
        assertThat(failures).as("nobody blew up").isEmpty();
        return new ArrayList<>(results);
    }
}
