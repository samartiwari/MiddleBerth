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

        long amount = fares.forClass(booking.getCoachClass());
        PaymentOrder order = paymentClient.createOrder(userId, requestId, amount);

        // Someone is paying now — the expiry job will give this hold extra time.
        // After the order, not before: if payment-service were down there would be
        // no payment in progress to protect.
        payments.markPaymentStarted(userId, requestId, Instant.now());
        return new PayNowResponse(order.orderId(), order.amountPaise(), order.currency(),
                order.keyId(), booking.getPayBy());
    }
}
