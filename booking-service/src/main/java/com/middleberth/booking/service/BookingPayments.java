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
}
