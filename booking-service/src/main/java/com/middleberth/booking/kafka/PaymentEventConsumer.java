package com.middleberth.booking.kafka;

import com.middleberth.booking.dto.PaymentEvent;
import com.middleberth.booking.dto.RefundRequest;
import com.middleberth.booking.service.BookingPayments;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Turns "paid" from payment-service into a confirmed booking — or into a refund,
 * if there is nothing left to give them.
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
    private final RefundRequestPublisher refunds;

    @KafkaListener(topics = PaymentEventsConfig.PAYMENT_EVENTS,
                   groupId = "booking-service",
                   containerFactory = "paymentEventsFactory")
    public void handle(PaymentEvent event) {
        if (!"PAID".equals(event.type())) {
            return;
        }
        BookingPayments.PaymentApplied result = payments.apply(event.userId(), event.requestId(), event.paidAt());

        if (result.refund()) {
            // We took their money and cannot give them anything — give it back.
            // Waits for Kafka; if that fails this throws, the paid event is retried,
            // and the refund is asked for again. payment-service refunds each
            // payment only once, so asking twice is harmless.
            refunds.publishAndWait(new RefundRequest(event.userId(), event.requestId(),
                    event.orderId(), event.paymentId(), event.amountPaise(), result.name()));
            log.warn("Refund requested for {}/{} ({})", event.userId(), event.requestId(), result);
            return;
        }

        switch (result) {
            case CONFIRMED, WAITLISTED ->
                    log.info("Booking {}/{} paid -> {}", event.userId(), event.requestId(), result);
            case HONOURED_LATE ->
                    log.info("Booking {}/{} paid in time but reached us late — honoured", event.userId(), event.requestId());
            default ->
                    log.debug("Duplicate paid event for {}/{}", event.userId(), event.requestId());
        }
    }
}
