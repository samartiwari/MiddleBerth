package com.middleberth.payment.service;

import com.middleberth.payment.domain.Payment;
import com.middleberth.payment.dto.PaymentEvent;
import com.middleberth.payment.dto.RazorpayWebhook;
import com.middleberth.payment.kafka.PaymentEventPublisher;
import com.middleberth.payment.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

/**
 * The money arrived. Its own class so @Transactional goes through the Spring
 * proxy — same rule as everywhere else in this project.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CapturedPaymentHandler {

    private final PaymentRepository paymentRepo;
    private final PaymentEventPublisher publisher;

    /**
     * The announcement goes out INSIDE this transaction, and waits for Kafka to
     * confirm. If Kafka cannot take it, publishAndWait throws, the whole
     * transaction rolls back — the PAID status with it — the webhook returns 500,
     * and Razorpay tries again later, when the payment is still CREATED.
     *
     * The tempting alternative is to mark PAID, commit, and announce afterwards.
     * That is the bug: if Kafka is down at that moment, PAID is already saved, the
     * retry sees PAID and does nothing, and a customer who paid never gets a ticket.
     * WebhookRetryTest fails if it is ever written that way.
     *
     * (The order of the two lines below does not matter — both are in the same
     * transaction and are undone together. What matters is that they ARE in the
     * same transaction, and that the publish is synchronous.)
     *
     * This is the opposite of the seat-count events in booking-service, which are
     * sent after the commit. Different failure, different choice:
     *
     *   seat counts   announcing a change that then rolled back is the worse
     *                 mistake, and a lost count is corrected by the next one
     *                 -> announce after commit
     *   payments      losing the event is the worse mistake — someone paid and
     *                 gets nothing — while a duplicate "paid" is harmless
     *                 -> announce inside the transaction
     *
     * The price here is that a crash after the publish but before the commit
     * announces the same payment twice. Safe direction: booking-service can ignore
     * a second "paid", it cannot invent a missing one.
     */
    @Transactional
    public WebhookOutcome captured(RazorpayWebhook.Entity entity) {
        Optional<Payment> found = paymentRepo.lockByOrderId(entity.orderId());
        if (found.isEmpty()) {
            log.warn("Webhook for unknown order {}", entity.orderId());
            return WebhookOutcome.UNKNOWN_ORDER;
        }

        Payment payment = found.get();
        if (payment.isPaid()) {
            return WebhookOutcome.DUPLICATE;
        }
        if (entity.amount() != payment.getAmountPaise()) {
            log.error("Order {} expected {} paise but {} were captured — needs a human",
                    entity.orderId(), payment.getAmountPaise(), entity.amount());
            return WebhookOutcome.AMOUNT_MISMATCH;
        }

        Instant paidAt = Instant.ofEpochSecond(entity.createdAt());

        publisher.publishAndWait(PaymentEvent.paid(payment.getUserId(), payment.getRequestId(),
                payment.getOrderId(), entity.id(), entity.amount(), paidAt));
        payment.markPaid(entity.id(), paidAt);
        return WebhookOutcome.PAID;
    }
}
