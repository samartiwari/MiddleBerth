package com.middleberth.booking.service;

import com.middleberth.booking.domain.Booking;
import com.middleberth.booking.domain.BookingStatus;
import com.middleberth.booking.domain.SeatStatus;
import com.middleberth.booking.dto.BookingResult;
import com.middleberth.booking.repository.BookingRepository;
import com.middleberth.booking.repository.SeatRepository;
import com.middleberth.booking.repository.WaitlistCounter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Money arrived for a booking.
 *
 * In 5a this is called directly, so the whole lifecycle can be tested before
 * payment-service exists. From 5c it is called when payment-service reports a
 * successful payment.
 */
@Service
@RequiredArgsConstructor
public class BookingPayments {

    private final BookingRepository bookingRepo;
    private final SeatRepository seatRepo;
    private final WaitlistCounter waitlistCounter;

    /**
     * What a "paid" event did.
     *
     * The rule for the ones marked refund: if we took someone's money and cannot
     * give them anything for it, we give it back.
     */
    public enum PaymentApplied {
        CONFIRMED(false),
        WAITLISTED(false),
        /** Paid before the deadline, reached us after the hold was released, and there was still room. */
        HONOURED_LATE(false),
        /** A second "paid" for the same booking. payment-service may announce twice. */
        ALREADY_PAID(false),

        /** Paid after the deadline. */
        PAID_AFTER_DEADLINE(true),
        /** Paid in time, but by the time it reached us every berth and waitlist slot was gone. */
        NOTHING_LEFT(true),
        /** Regretted — there was never anything to pay for. */
        NOT_PAYABLE(true),
        UNKNOWN_BOOKING(true);

        private final boolean refund;

        PaymentApplied(boolean refund) {
            this.refund = refund;
        }

        public boolean refund() {
            return refund;
        }
    }

    /**
     * The booking row is locked first. The expiry job uses SKIP LOCKED, so while
     * this holds the lock the expiry job steps over this booking — and if the
     * expiry job got there first, this waits, then sees EXPIRED and refuses.
     * Either way the two can never both act on it.
     *
     * What happens to a payment that arrives after expiry is 5d.
     */
    @Transactional
    public BookingResult markPaid(Long userId, String requestId, Instant paidAt) {
        Booking booking = bookingRepo.lockByUserIdAndRequestId(userId, requestId)
                .orElseThrow(() -> new IllegalArgumentException("No booking " + requestId + " for user " + userId));

        booking.markPaid(paidAt);   // throws if it is not a hold any more

        if (booking.getStatus() == BookingStatus.CONFIRMED) {
            seatRepo.findById(booking.getSeatId()).orElseThrow().setStatus(SeatStatus.CONFIRMED);
            return BookingResult.confirmed(seatRepo.findById(booking.getSeatId()).orElseThrow().label());
        }
        return BookingResult.waitlisted(booking.getWaitlistPos());
    }

    /**
     * What the payment-events consumer calls. Unlike markPaid it never throws for
     * an ordinary situation — a duplicate, a late payment, an unknown booking —
     * because an exception in a Kafka listener makes it retry the same message, and
     * a message that can never succeed would block every payment behind it.
     */
    @Transactional
    public PaymentApplied apply(Long userId, String requestId, Instant paidAt) {
        var found = bookingRepo.lockByUserIdAndRequestId(userId, requestId);
        if (found.isEmpty()) {
            return PaymentApplied.UNKNOWN_BOOKING;
        }
        Booking booking = found.get();
        return switch (booking.getStatus()) {
            case HELD, WAITLIST_HELD -> {
                booking.markPaid(paidAt);
                if (booking.getStatus() == BookingStatus.CONFIRMED) {
                    seatRepo.findById(booking.getSeatId()).orElseThrow().setStatus(SeatStatus.CONFIRMED);
                    yield PaymentApplied.CONFIRMED;
                }
                yield PaymentApplied.WAITLISTED;
            }
            case CONFIRMED, WAITLISTED -> PaymentApplied.ALREADY_PAID;
            case EXPIRED -> lateArrival(booking, paidAt);
            case REGRETTED -> PaymentApplied.NOT_PAYABLE;
        };
    }

    /**
     * Money arrived for a hold that has already been released.
     *
     * From initial.md: judge by when they PAID, not when we found out. The deadline
     * is still on the booking — expire() keeps it for exactly this.
     *
     *   paid after the deadline   -> it really was late. Refund.
     *   paid before it            -> we were the slow ones. Honour it if we still
     *                                can: a free berth first, else a waitlist slot.
     *                                Only if both are gone, refund.
     *
     * A free berth goes to them even if they were on the waitlist before — if one
     * is free, nobody is waiting for it (new bookings take free berths before anyone
     * joins the waitlist), so they are not jumping anyone.
     */
    private PaymentApplied lateArrival(Booking booking, Instant paidAt) {
        if (paidAt == null || paidAt.isAfter(booking.getPayBy())) {
            return PaymentApplied.PAID_AFTER_DEADLINE;
        }

        var berth = seatRepo.claimFreeSeat(booking.getTrainId(), booking.getTravelDate(), booking.getCoachClass());
        if (berth.isPresent()) {
            berth.get().setStatus(SeatStatus.CONFIRMED);
            booking.reinstateWithBerth(berth.get().getId(), paidAt);
            return PaymentApplied.HONOURED_LATE;
        }

        var slot = waitlistCounter.take(booking.getTrainId(), booking.getTravelDate(), booking.getCoachClass());
        if (slot.isPresent()) {
            booking.reinstateOnWaitlist(slot.get(), paidAt);
            return PaymentApplied.HONOURED_LATE;
        }
        return PaymentApplied.NOTHING_LEFT;
    }

    /**
     * Pay Now created an order for this hold. From now on the expiry job gives it
     * extra time instead of releasing it in the middle of someone paying.
     */
    @Transactional
    public void markPaymentStarted(Long userId, String requestId, Instant at) {
        bookingRepo.lockByUserIdAndRequestId(userId, requestId).ifPresent(b -> b.markPaymentStarted(at));
    }
}
