package com.middleberth.booking.service;

import com.middleberth.booking.domain.Booking;
import com.middleberth.booking.dto.PayNowResponse;
import com.middleberth.booking.exception.BookingNotFoundException;
import com.middleberth.booking.exception.NotPayableException;
import com.middleberth.booking.payment.FareSettings;
import com.middleberth.booking.payment.PaymentClient;
import com.middleberth.booking.payment.PaymentOrder;
import com.middleberth.booking.repository.BookingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Service
@RequiredArgsConstructor
public class PayNowService {

    private final BookingRepository bookingRepo;
    private final PaymentClient paymentClient;
    private final FareSettings fares;
    private final BookingPayments payments;

    /**
     * The user clicked Pay Now. booking-service is the one that knows whether this
     * booking is theirs and still a live hold, so it checks — then asks
     * payment-service for an order.
     *
     * Not @Transactional, on purpose: the call to payment-service is a network call,
     * and holding a database connection while waiting on it is how the pool runs
     * dry. The booking is read in its own short query, then the connection is
     * free before the HTTP call starts.
     *
     * This runs in the user's own request, not in the Kafka consumer. One train's
     * bookings are processed one at a time there, so a ~100ms payment call inside
     * it would make everyone behind wait.
     */
    public PayNowResponse payNow(Long userId, String requestId) {
        Booking booking = bookingRepo.findByUserIdAndRequestId(userId, requestId)
                .orElseThrow(() -> new BookingNotFoundException(requestId));   // not yours looks the same as missing

        if (!booking.getStatus().isHold()) {
            throw new NotPayableException("Booking is " + booking.getStatus() + ", there is nothing to pay for");
        }
        // The cushion is for payments ALREADY started before the deadline. Starting a
        // new one after it is refused.
        if (Instant.now().isAfter(booking.getPayBy())) {
            throw new NotPayableException("The hold expired at " + booking.getPayBy());
        }

        // Base fare is the ticket. The convenience fee on top is what pays the
        // gateway's cut — taken on the way in and never returned — so that a
        // cancellation can refund the whole fare without the company having to
        // find the difference from somewhere else. IRCTC works the same way.
        String coachClass = booking.getCoachClass();
        long baseFare = fares.baseFare(coachClass);
        long total = fares.totalCharge(coachClass);

        // payment-service is told BOTH: what to charge, and how much of it is the
        // ticket. It is the one that later hands money back, and it must not have
        // to know anything about how a fare is built up to do that.
        PaymentOrder order = paymentClient.createOrder(userId, requestId, total, baseFare);

        // Someone is paying now — the expiry job will give this hold extra time.
        // After the order, not before: if payment-service were down there would be
        // no payment in progress to protect.
        payments.markPaymentStarted(userId, requestId, Instant.now());
        return new PayNowResponse(order.orderId(), order.amountPaise(), order.currency(),
                order.keyId(), baseFare, total - baseFare, booking.getPayBy());
    }
}
