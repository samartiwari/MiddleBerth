package com.middleberth.booking.kafka;

import com.middleberth.booking.dto.PaymentEvent;
import com.middleberth.booking.service.BookingPayments;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Turns "paid" from payment-service into a confirmed booking.
 *
 * payment-service announces at least once — it may announce the same payment
 * twice after a crash — so applying it twice must be harmless. apply() sees the
 * booking is already paid and does nothing.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PaymentEventConsumer {

    private final BookingPayments payments;

    @KafkaListener(topics = PaymentEventsConfig.PAYMENT_EVENTS,
                   groupId = "booking-service",
                   containerFactory = "paymentEventsFactory")
    public void handle(PaymentEvent event) {
        if (!"PAID".equals(event.type())) {
            return;
        }
        BookingPayments.PaymentApplied result = payments.apply(event.userId(), event.requestId(), event.paidAt());

        switch (result) {
            case CONFIRMED, WAITLISTED ->
                    log.info("Booking {}/{} paid -> {}", event.userId(), event.requestId(), result);
            case ALREADY_PAID ->
                    log.debug("Duplicate paid event for {}/{}", event.userId(), event.requestId());
            case TOO_LATE ->
                    // The customer's money arrived after the hold was released. They
                    // must get it back — that is phase 5d. Logged loudly until then.
                    log.warn("Payment {} for {}/{} arrived after the hold expired — needs a refund",
                            event.paymentId(), event.userId(), event.requestId());
            case NOT_PAYABLE, UNKNOWN_BOOKING ->
                    log.warn("Payment {} for {}/{} does not match a payable booking: {}",
                            event.paymentId(), event.userId(), event.requestId(), result);
        }
    }
}
