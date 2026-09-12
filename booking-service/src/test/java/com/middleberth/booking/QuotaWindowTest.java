package com.middleberth.booking;

import com.middleberth.booking.domain.Seat;
import com.middleberth.booking.domain.SeatStatus;
import com.middleberth.booking.domain.Train;
import com.middleberth.booking.dto.BookingCommand;
import com.middleberth.booking.repository.BookingRepository;
import com.middleberth.booking.repository.SeatRepository;
import com.middleberth.booking.repository.TrainRepository;
import com.middleberth.booking.service.BookingService;
import com.middleberth.booking.service.QuotaWindowJob;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The booking window rolls forward one day, every day.
 *
 *     a week ago       today         tomorrow
 *     deleted    <--   on sale  <--  opens at noon
 *
 * Opening is the obvious half. Deleting is the half that matters for a system
 * meant to run unattended: without it, every day adds a few hundred berths and a
 * few thousand bookings that nobody will ever read again, and the database grows
 * for ever. With it, storage settles at about nine days and stays there.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class QuotaWindowTest {

    @Autowired QuotaWindowJob quotaWindow;
    @Autowired BookingService bookingService;
    @Autowired BookingRepository bookingRepo;
    @Autowired SeatRepository seatRepo;
    @Autowired TrainRepository trainRepo;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;

    private Long trainId;

    @BeforeEach
    void seed() {
        TestDatabase.wipe(jdbc);
        tx.executeWithoutResult(s -> {
            Train train = trainRepo.save(new Train("12951", "Mumbai Rajdhani"));
            trainId = train.getId();
        });
        // What this train HAS — not what is on sale on any particular date.
        jdbc.update("INSERT INTO train_quota (train_id, coach_class, coach, berths) VALUES (?, '3A', 'B2', 24)", trainId);
        jdbc.update("INSERT INTO train_quota (train_id, coach_class, coach, berths) VALUES (?, 'SL', 'S4', 72)", trainId);
    }

    @Test
    void rolling_the_window_puts_tomorrow_on_sale() {
        LocalDate today = LocalDate.of(2026, 8, 25);

        int opened = quotaWindow.roll(today);

        assertThat(opened).as("24 in 3A and 72 in sleeper").isEqualTo(96);
        assertThat(berthsOn(today.plusDays(1), "3A")).isEqualTo(24);
        assertThat(berthsOn(today.plusDays(1), "SL")).isEqualTo(72);
        assertThat(berthsOn(today, "3A")).as("today was never opened by this run").isZero();
    }

    /** It runs on every pod, and it runs again tomorrow. Neither may duplicate a berth. */
    @Test
    void rolling_twice_creates_nothing_the_second_time() {
        LocalDate today = LocalDate.of(2026, 8, 25);
        quotaWindow.roll(today);

        int again = quotaWindow.roll(today);

        assertThat(again).isZero();
        assertThat(berthsOn(today.plusDays(1), "3A")).isEqualTo(24);
    }

    /**
     * The one that would be a disaster. "Reopening" a date that is on sale must
     * never hand somebody's held berth back to the pool.
     */
    @Test
    void a_berth_somebody_is_holding_is_never_reset() {
        LocalDate today = LocalDate.of(2026, 8, 25);
        LocalDate onSale = today.plusDays(1);
        quotaWindow.roll(today);

        bookingService.book(new BookingCommand("A7X2", 5512L, "12951", onSale, "3A", TestPassenger.SOMEONE));
        Long heldSeat = bookingRepo.findByUserIdAndRequestId(5512L, "A7X2").orElseThrow().getSeatId();
        assertThat(seatRepo.findById(heldSeat).orElseThrow().getStatus()).isEqualTo(SeatStatus.HELD);

        quotaWindow.roll(today);

        assertThat(seatRepo.findById(heldSeat).orElseThrow().getStatus())
                .as("still theirs").isEqualTo(SeatStatus.HELD);
        assertThat(berthsOn(onSale, "3A")).as("and no extra berths appeared").isEqualTo(24);
    }

    /**
     * The half that keeps storage flat. A date a week gone takes its bookings with
     * it — bookings first, because a booking points at a seat.
     */
    @Test
    void travel_dates_older_than_a_week_are_deleted_with_their_bookings() {
        LocalDate today = LocalDate.of(2026, 8, 25);
        LocalDate longGone = today.minusDays(30);
        LocalDate recent = today.minusDays(2);

        putOnSale(longGone);
        putOnSale(recent);
        bookingService.book(new BookingCommand("OLD", 1L, "12951", longGone, "3A", TestPassenger.SOMEONE));
        bookingService.book(new BookingCommand("RECENT", 2L, "12951", recent, "3A", TestPassenger.SOMEONE));

        quotaWindow.roll(today);

        assertThat(berthsOn(longGone, "3A")).as("a month ago is gone").isZero();
        assertThat(bookingRepo.findByUserIdAndRequestId(1L, "OLD")).isEmpty();

        assertThat(berthsOn(recent, "3A")).as("two days ago is inside the week").isEqualTo(4);
        assertThat(bookingRepo.findByUserIdAndRequestId(2L, "RECENT")).isPresent();
    }

    /** Storage has to stop growing, which is the whole point. */
    @Test
    void running_for_many_days_does_not_grow_the_database() {
        LocalDate day = LocalDate.of(2026, 8, 1);
        for (int i = 0; i < 10; i++) {
            quotaWindow.roll(day.plusDays(i));
        }
        long afterTenDays = seatRepo.count();

        for (int i = 10; i < 40; i++) {
            quotaWindow.roll(day.plusDays(i));
        }

        assertThat(seatRepo.count())
                .as("thirty more days later, the same amount of data")
                .isEqualTo(afterTenDays);
    }

    /**
     * A note that never went out is somebody's missing mail, whatever its age.
     *
     * Asserted by whether the ROW still exists, not by whether it is still unsent:
     * the outbox job runs every second and may well have sent it by now. Written
     * the other way round first, and a live run showed the race immediately —
     * the note had been published, not purged, and the test would have failed for
     * the wrong reason on a slow day.
     */
    @Test
    void an_unsent_note_is_never_purged() {
        jdbc.update("""
                INSERT INTO outbox (booking_key, type, payload, created_at, sent_at)
                VALUES ('1|OLD', 'TICKET_CONFIRMED', '{}', now() - interval '60 days', NULL)
                """);
        jdbc.update("""
                INSERT INTO outbox (booking_key, type, payload, created_at, sent_at)
                VALUES ('2|OLD', 'TICKET_CONFIRMED', '{}', now() - interval '60 days', now() - interval '60 days')
                """);

        quotaWindow.roll(LocalDate.of(2026, 8, 25));

        assertThat(rowsFor("1|OLD")).as("never sent, so never thrown away").isEqualTo(1);
        assertThat(rowsFor("2|OLD")).as("sent long ago, no longer needed").isZero();
    }

    private int rowsFor(String bookingKey) {
        return jdbc.queryForObject("SELECT count(*) FROM outbox WHERE booking_key = ?",
                Integer.class, bookingKey);
    }

    // ---------- helpers ----------

    /** A few berths on a date, without going through the job. */
    private void putOnSale(LocalDate date) {
        tx.executeWithoutResult(s -> {
            for (int n = 1; n <= 4; n++) {
                seatRepo.save(new Seat(trainId, date, "3A", "B2", String.valueOf(n), SeatStatus.FREE));
            }
        });
    }

    private int berthsOn(LocalDate date, String coachClass) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM seat WHERE travel_date = ? AND coach_class = ?",
                Integer.class, date, coachClass);
    }
}
