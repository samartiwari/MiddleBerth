package com.middleberth.booking.service;

import com.middleberth.booking.domain.Booking;
import com.middleberth.booking.domain.BookingStatus;
import com.middleberth.booking.domain.SeatStatus;
import com.middleberth.booking.dto.BookingResult;
import com.middleberth.booking.repository.BookingRepository;
import com.middleberth.booking.repository.SeatRepository;
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

    /** What a "paid" event did. None of these are errors — see apply(). */
    public enum PaymentApplied {
        CONFIRMED,
        WAITLISTED,
        /** A second "paid" for the same booking. payment-service may announce twice. */
        ALREADY_PAID,
        /** The hold expired before the money arrived. What to do about it is 5d. */
        TOO_LATE,
        /** Regretted — there was never anything to pay for. */
        NOT_PAYABLE,
        UNKNOWN_BOOKING
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
            case EXPIRED -> PaymentApplied.TOO_LATE;
            case REGRETTED -> PaymentApplied.NOT_PAYABLE;
        };
    }
}
