package com.middleberth.payment.service;

import com.middleberth.payment.domain.Payment;
import com.middleberth.payment.domain.PaymentStatus;
import com.middleberth.payment.dto.RazorpayWebhook;
import com.middleberth.payment.gateway.PaymentGateway;
import com.middleberth.payment.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Asks the gateway directly about orders nobody has paid for yet.
 *
 * From initial.md: the webhook is the fast path, this job is the safety net. A
 * webhook can simply never arrive — a network failure between Razorpay and us —
 * and without this the customer's money would sit at Razorpay while their booking
 * expired.
 *
 * What it finds goes down exactly the same path as a webhook (CapturedPaymentHandler),
 * so the amount check, the duplicate check and the announcement all apply. If the
 * webhook turns up afterwards, it is just a duplicate.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ReconciliationJob {

    private final PaymentRepository paymentRepo;
    private final PaymentGateway gateway;
    private final CapturedPaymentHandler captured;
    private final ReconcileSettings settings;

    @Scheduled(fixedDelayString = "${middleberth.reconcile.interval}")
    public void run() {
        reconcile(Instant.now());
    }

    /** Public so tests can pass a chosen "now" instead of waiting. Returns how many it found. */
    public int reconcile(Instant now) {
        List<Payment> waiting = paymentRepo.findByStatusAndCreatedAtBetween(PaymentStatus.CREATED,
                now.minus(settings.maxAge()).atOffset(ZoneOffset.UTC),
                now.minus(settings.minAge()).atOffset(ZoneOffset.UTC));

        int found = 0;
        for (Payment payment : waiting) {
            var paid = gateway.findCapturedPayment(payment.getOrderId());   // network call, no transaction held
            if (paid.isEmpty()) {
                continue;
            }
            WebhookOutcome outcome = captured.captured(new RazorpayWebhook.Entity(
                    paid.get().paymentId(), payment.getOrderId(), paid.get().amountPaise(),
                    payment.getCurrency(), "captured", paid.get().createdAt()));
            if (outcome == WebhookOutcome.PAID) {
                found++;
                log.warn("Order {} was paid but its webhook never arrived — recovered", payment.getOrderId());
            }
        }
        return found;
    }
}
