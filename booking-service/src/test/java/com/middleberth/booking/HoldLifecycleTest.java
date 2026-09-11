package com.middleberth.booking;

import com.middleberth.booking.domain.Booking;
import com.middleberth.booking.domain.BookingStatus;
import com.middleberth.booking.domain.Seat;
import com.middleberth.booking.domain.SeatStatus;
import com.middleberth.booking.domain.Train;
import com.middleberth.booking.dto.BookingCommand;
import com.middleberth.booking.dto.BookingResult;
import com.middleberth.booking.repository.BookingRepository;
import com.middleberth.booking.repository.SeatRepository;
import com.middleberth.booking.repository.TrainRepository;
import com.middleberth.booking.service.BookingPayments;
import com.middleberth.booking.service.BookingService;
import com.middleberth.booking.service.HoldExpiryJob;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 5a: a booking is a hold with a deadline, and what happens after.
 *
 * Nothing here waits for real time. The expiry job takes a "now" as an argument,
 * and individual deadlines are moved into the past directly when a test needs
 * one hold expired and another still alive.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class HoldLifecycleTest {

    private static final LocalDate DATE = LocalDate.of(2026, 8, 25);
    private static final String TRAIN = "12951";
    private static final String CLASS = "3A";

    @Autowired BookingService bookingService;
    @Autowired BookingPayments payments;
    @Autowired HoldExpiryJob expiryJob;
    @Autowired BookingRepository bookingRepo;
    @Autowired SeatRepository seatRepo;
    @Autowired TrainRepository trainRepo;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void wipe() {
        TestDatabase.wipe(jdbc);
    }

    // ---------- paying ----------

    @Test
    void paying_for_a_held_berth_confirms_it() {
        seed(2);
        book("A", 1);

        BookingResult paid = payments.markPaid(1L, "A", Instant.now());

        assertThat(paid.status()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(booking("A", 1).getPayBy()).as("no deadline any more").isNull();
        assertThat(seatOf("A", 1).getStatus()).isEqualTo(SeatStatus.CONFIRMED);
    }

    @Test
    void paying_for_a_waitlist_slot_keeps_the_place() {
        seed(1);
        book("A", 1);                                    // the only berth
        BookingResult wl = book("B", 2);
        assertThat(wl.status()).isEqualTo(BookingStatus.WAITLIST_HELD);

        BookingResult paid = payments.markPaid(2L, "B", Instant.now());

        assertThat(paid.status()).isEqualTo(BookingStatus.WAITLISTED);
        assertThat(paid.position()).isEqualTo(1);
    }

    // ---------- expiring ----------

    @Test
    void an_unpaid_berth_goes_back_on_sale_when_nobody_is_waiting() {
        seed(1);
        book("A", 1);

        int released = expiryJob.releaseExpired(wellAfterEveryDeadline());

        assertThat(released).isEqualTo(1);
        assertThat(booking("A", 1).getStatus()).isEqualTo(BookingStatus.EXPIRED);
        assertThat(seatRepo.findAll()).allMatch(s -> s.getStatus() == SeatStatus.FREE);

        // and it really is free — the next person gets it
        assertThat(book("C", 3).status()).isEqualTo(BookingStatus.HELD);
    }

    /** From initial.md: released seats flow down the waitlist, not back to a free-for-all. */
    @Test
    void an_expired_berth_goes_straight_to_the_next_paid_waitlister() {
        seed(1);
        book("A", 1);                                    // holds the berth, never pays
        book("B", 2);
        payments.markPaid(2L, "B", Instant.now());       // B paid for the waitlist
        Long berth = seatOf("A", 1).getId();

        expiryJob.releaseExpired(wellAfterEveryDeadline());

        assertThat(booking("A", 1).getStatus()).isEqualTo(BookingStatus.EXPIRED);
        Booking b = booking("B", 2);
        assertThat(b.getStatus()).as("promoted").isEqualTo(BookingStatus.CONFIRMED);
        assertThat(b.getSeatId()).as("to the berth A let go").isEqualTo(berth);
        assertThat(seatRepo.findById(berth).orElseThrow().getStatus())
                .as("never went back to FREE, so nobody could jump the queue")
                .isEqualTo(SeatStatus.CONFIRMED);
    }

    @Test
    void someone_who_has_not_paid_is_skipped_for_promotion() {
        seed(2);
        book("A1", 1);
        book("A2", 2);                                   // both berths held
        book("B", 3);                                    // WL 1 — does NOT pay
        book("C", 4);                                    // WL 2 — pays
        payments.markPaid(4L, "C", Instant.now());

        pastDeadline("A1", 1);                           // only A1's hold is over
        expiryJob.releaseExpired(Instant.now());

        assertThat(booking("C", 4).getStatus()).as("paid, so promoted").isEqualTo(BookingStatus.CONFIRMED);
        Booking b = booking("B", 3);
        assertThat(b.getStatus()).as("unpaid, still holding WL 1").isEqualTo(BookingStatus.WAITLIST_HELD);
        assertThat(b.getWaitlistPos()).isEqualTo(1);
    }

    /** Regret is temporary — and ticket numbers are never reused. */
    @Test
    void an_expired_waitlist_hold_frees_a_slot_for_someone_else() {
        seed(1);                                         // 1 berth, so a waitlist cap of 1
        book("A", 1);
        book("B", 2);                                    // takes the only waitlist slot
        assertThat(book("C", 3).status()).as("full").isEqualTo(BookingStatus.REGRETTED);

        pastDeadline("B", 2);
        expiryJob.releaseExpired(Instant.now());
        assertThat(booking("B", 2).getStatus()).isEqualTo(BookingStatus.EXPIRED);

        BookingResult d = book("D", 4);
        assertThat(d.status()).as("the slot reopened").isEqualTo(BookingStatus.WAITLIST_HELD);
        assertThat(d.position()).as("number 2, not 1 — numbers only go up").isEqualTo(2);
    }

    /** From initial.md: judge by when they paid, not when we found out. */
    @Test
    void the_cushion_keeps_a_hold_alive_just_past_its_deadline() {
        seed(1);
        Instant payBy = book("A", 1).payBy();

        assertThat(expiryJob.releaseExpired(payBy.plusSeconds(30)))
                .as("30s late — inside the 1 minute cushion").isZero();
        assertThat(booking("A", 1).getStatus()).isEqualTo(BookingStatus.HELD);

        assertThat(expiryJob.releaseExpired(payBy.plusSeconds(61)))
                .as("past the cushion").isEqualTo(1);
    }

    @Test
    void an_expired_booking_cannot_be_paid_for() {
        seed(1);
        book("A", 1);
        expiryJob.releaseExpired(wellAfterEveryDeadline());

        // What to do with money that arrives late is phase 5d — for now it is refused.
        assertThatThrownBy(() -> payments.markPaid(1L, "A", Instant.now()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("EXPIRED");
    }

    // ---------- many pods at once ----------

    /**
     * Every pod runs the expiry job. SKIP LOCKED means they take different
     * batches, so each hold is released exactly once.
     */
    @Test
    void four_expiry_jobs_at_once_release_every_hold_exactly_once() throws Exception {
        int holds = 120;
        seed(holds);
        for (int i = 0; i < holds; i++) {
            book("H" + i, 1000 + i);
        }

        AtomicInteger released = new AtomicInteger();
        Queue<Throwable> failures = new ConcurrentLinkedQueue<>();
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(4);
        ExecutorService pool = Executors.newFixedThreadPool(4);
        Instant later = wellAfterEveryDeadline();

        for (int t = 0; t < 4; t++) {
            pool.submit(() -> {
                try {
                    go.await();
                    released.addAndGet(expiryJob.releaseExpired(later));
                } catch (Throwable e) {
                    failures.add(e);
                } finally {
                    done.countDown();
                }
            });
        }
        go.countDown();
        assertThat(done.await(60, TimeUnit.SECONDS)).isTrue();
        pool.shutdownNow();

        System.out.printf("%n4 expiry jobs released %d of %d holds%n", released.get(), holds);
        assertThat(failures).isEmpty();
        assertThat(released.get()).as("each hold released exactly once, never twice").isEqualTo(holds);
        assertThat(bookingRepo.findAll()).allMatch(b -> b.getStatus() == BookingStatus.EXPIRED);
        assertThat(seatRepo.findAll()).allMatch(s -> s.getStatus() == SeatStatus.FREE);
    }

    // ---------- helpers ----------

    private void seed(int berths) {
        tx.executeWithoutResult(s -> {
            Train train = trainRepo.save(new Train(TRAIN, "Mumbai Rajdhani"));
            for (int n = 1; n <= berths; n++) {
                seatRepo.save(new Seat(train.getId(), DATE, CLASS, "B2", String.valueOf(n), SeatStatus.FREE));
            }
        });
    }

    private BookingResult book(String requestId, long userId) {
        return bookingService.book(new BookingCommand(requestId, userId, TRAIN, DATE, CLASS));
    }

    private Booking booking(String requestId, long userId) {
        return bookingRepo.findByUserIdAndRequestId(userId, requestId).orElseThrow();
    }

    private Seat seatOf(String requestId, long userId) {
        return seatRepo.findById(booking(requestId, userId).getSeatId()).orElseThrow();
    }

    /** Far enough ahead that every hold is past its deadline AND the cushion. */
    private Instant wellAfterEveryDeadline() {
        return Instant.now().plus(Duration.ofMinutes(10));
    }

    /** Moves one booking's deadline into the past, leaving every other hold alone. */
    private void pastDeadline(String requestId, long userId) {
        jdbc.update("UPDATE booking SET pay_by = now() - interval '10 minutes' WHERE request_id = ? AND user_id = ?",
                requestId, userId);
    }
}
