package com.middleberth.booking.service;

import com.middleberth.booking.domain.Booking;
import com.middleberth.booking.domain.PassengerSnapshot;
import com.middleberth.booking.domain.Seat;
import com.middleberth.booking.domain.SeatStatus;
import com.middleberth.booking.dto.BookingCommand;
import com.middleberth.booking.dto.BookingResult;
import com.middleberth.booking.repository.BookingRepository;
import com.middleberth.booking.repository.SeatRepository;
import com.middleberth.booking.repository.WaitlistCounter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

/**
 * The atomic bit: take a berth and write the booking, or write a waitlist row.
 *
 * Separate class on purpose. BookingService has to catch the unique-constraint
 * violation and then read the row that won — and that read has to happen in a
 * NEW transaction, because a constraint violation aborts the one it happened in.
 * Calling a @Transactional method from another method in the same class does not
 * go through the Spring proxy, so the annotation would be silently ignored.
 */
@Component
@RequiredArgsConstructor
class BookingTransaction {

    private final SeatRepository seatRepo;
    private final BookingRepository bookingRepo;
    private final WaitlistCounter waitlistCounter;
    private final HoldSettings holds;

    /**
     * Three outcomes, and the first two are both a HOLD with a deadline:
     *
     *   berth free       -> hold the berth,         pay within 5 minutes
     *   berth gone       -> hold a waitlist slot,   pay within 5 minutes
     *   waitlist full    -> REGRET, nothing held
     *
     * If the insert is rejected as a duplicate request, the whole transaction rolls
     * back — the berth goes back to FREE and the waitlist counter goes back down, so
     * a retry never burns a berth or a number.
     */
    @Transactional
    BookingResult claimOrWaitlist(BookingCommand cmd, Long trainId) {
        Instant payBy = Instant.now().plus(holds.payWithin());

        //try to claim a seat
        Optional<Seat> claimed =
                seatRepo.claimFreeSeat(trainId, cmd.travelDate(), cmd.coachClass());

        if (claimed.isPresent()) {
            Seat seat = claimed.get();
            seat.setStatus(SeatStatus.HELD);       // locked until this commits
            bookingRepo.saveAndFlush(Booking.held(
                    cmd.requestId(), cmd.userId(), trainId,
                    cmd.travelDate(), cmd.coachClass(), snapshotOf(cmd), seat.getId(), payBy));
            return BookingResult.held(seat.label(), payBy);
        }

        Optional<Integer> number = waitlistCounter.take(trainId, cmd.travelDate(), cmd.coachClass());

        if (number.isPresent()) {
            bookingRepo.saveAndFlush(Booking.waitlistHeld(
                    cmd.requestId(), cmd.userId(), trainId,
                    cmd.travelDate(), cmd.coachClass(), snapshotOf(cmd), number.get(), payBy));
            return BookingResult.waitlistHeld(number.get(), payBy);
        }

        // Nothing is written. A regret holds no berth, no waitlist number and no
        // money, so there is nothing about it worth keeping — it used to be a row
        // whose whole content was the word "no". The answer goes to the person
        // asking and nowhere else.
        //
        // Almost nobody gets here any more: the door turns them away before the
        // queue. This is only for the few who were told there was room and lost it
        // in the seconds between.
        //
        // With no row there is also no UNIQUE constraint holding this request id,
        // so a redelivered message is free to try again — and if a slot has opened
        // up by then, that person gets it instead of their old "no". That is fine,
        // and arguably better: the id still cannot produce two bookings, because
        // the moment it produces one, the constraint exists.
        return BookingResult.regretted();
    }

    /**
     * Copied from the request, not read from the master list. The ticket records
     * who it was issued to, and editing that list later must not change it.
     */
    private static PassengerSnapshot snapshotOf(BookingCommand cmd) {
        var p = cmd.passenger();
        return p == null ? null : new PassengerSnapshot(p.name(), p.email(), p.phone());
    }
}
