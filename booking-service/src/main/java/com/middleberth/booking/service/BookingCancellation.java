package com.middleberth.booking.service;

import com.middleberth.booking.domain.Booking;
import com.middleberth.booking.domain.BookingStatus;
import com.middleberth.booking.domain.Seat;
import com.middleberth.booking.domain.SeatStatus;
import com.middleberth.booking.exception.BookingNotFoundException;
import com.middleberth.booking.exception.NotCancellableException;
import com.middleberth.booking.repository.BookingRepository;
import com.middleberth.booking.repository.SeatRepository;
import com.middleberth.booking.repository.WaitlistCounter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * The passenger gives the ticket up.
 *
 * A released berth behaves exactly as it does when a hold expires: it goes
 * straight to the next paid waitlister, inside this transaction, and never passes
 * through FREE. Otherwise somebody refreshing the search page could grab it in
 * between and jump a queue of people who have already paid.
 *
 * Its own class so @Transactional goes through the Spring proxy — the same rule
 * as everywhere else here.
 */
@Service
@RequiredArgsConstructor
public class BookingCancellation {

    private final BookingRepository bookingRepo;
    private final SeatRepository seatRepo;
    private final WaitlistCounter waitlistCounter;
    private final Outbox outbox;

    /**
     * Locks the booking first, for the same reason paying does: the expiry job
     * uses SKIP LOCKED, so while this holds the lock that job steps over this
     * booking, and the two can never both act on it.
     *
     * No cancellation fee. Real railways charge one and the rules fill a page;
     * initial.md rules that out, so this gives the whole amount back.
     */
    @Transactional
    public CancelOutcome cancel(Long userId, String requestId) {
        Booking booking = bookingRepo.lockByUserIdAndRequestId(userId, requestId)
                .orElseThrow(() -> new BookingNotFoundException(requestId));

        BookingStatus was = booking.getStatus();
        boolean paid = was == BookingStatus.CONFIRMED || was == BookingStatus.WAITLISTED;

        if (!paid && !was.isHold()) {
            // Already expired, already cancelled, or never held anything.
            throw new NotCancellableException(requestId, was.name());
        }

        if (booking.getSeatId() != null) {
            releaseBerth(booking);
        } else {
            // A waitlist place, held or paid — hand the slot back so somebody
            // waiting behind the cap can have it.
            waitlistCounter.release(booking.getTrainId(), booking.getTravelDate(), booking.getCoachClass());
        }

        booking.cancel();

        // Money only goes back if money came in. A hold nobody paid for owes nothing.
        if (paid) {
            outbox.bookingCancelled(booking, "CANCELLED_BY_PASSENGER");
        }
        return new CancelOutcome(booking.getPnr(), paid);
    }

    /** The berth, to the next paid waitlister if there is one. */
    private void releaseBerth(Booking booking) {
        Seat berth = seatRepo.findById(booking.getSeatId()).orElseThrow();
        Optional<Booking> next = bookingRepo.lockNextWaitlisted(
                booking.getTrainId(), booking.getTravelDate(), booking.getCoachClass());

        if (next.isPresent()) {
            next.get().promoteTo(berth.getId());
            berth.setStatus(SeatStatus.CONFIRMED);   // they paid for their waitlisted ticket
            waitlistCounter.release(booking.getTrainId(), booking.getTravelDate(), booking.getCoachClass());
            outbox.ticketConfirmed(next.get(), berth.label());   // the mail nobody was expecting
        } else {
            berth.setStatus(SeatStatus.FREE);
        }
    }
}
