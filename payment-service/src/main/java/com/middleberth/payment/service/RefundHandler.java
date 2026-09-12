package com.middleberth.payment.service;

import com.middleberth.payment.domain.Payment;
import com.middleberth.payment.domain.PaymentStatus;
import com.middleberth.payment.dto.RefundRequest;
import com.middleberth.payment.gateway.PaymentGateway;
import com.middleberth.payment.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Optional;

/**
 * Gives a customer their money back.
 *
 *   1. look the payment up                      — its own short query
 *   2. ask the gateway to refund it             — network call, NO connection held
 *   3. record the refund                        — its own short transaction
 *
 * Holding a database connection across the gateway call is how a pool runs dry,
 * so the three are kept apart. What makes that safe is the gateway contract:
 * refundInFull is safe to repeat. A crash between 2 and 3 means this runs again,
 * step 2 hands back the refund that already happened, and step 3 records it.
 *
 * Two requests for the same booking cannot race each other here either: they
 * share a Kafka key, so the same consumer thread handles them one at a time.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RefundHandler {

    private final PaymentRepository paymentRepo;
    private final PaymentGateway gateway;
    private final TransactionTemplate tx;

    public RefundOutcome refund(RefundRequest request) {
        Optional<Payment> found = find(request);
        if (found.isEmpty()) {
            log.error("Refund asked for a payment we do not have: order {} / booking {}|{} — needs a human",
                    request.orderId(), request.userId(), request.requestId());
            return RefundOutcome.UNKNOWN_ORDER;
        }
        Payment payment = found.get();
        if (payment.getStatus() == PaymentStatus.REFUNDED) {
            return RefundOutcome.ALREADY_REFUNDED;
        }
        if (payment.getStatus() != PaymentStatus.PAID) {
            throw new NotCapturedYetException(request.orderId());   // retried — see the exception
        }

        String refundId = gateway.refundInFull(payment.getRazorpayPaymentId(), payment.getAmountPaise());

        tx.executeWithoutResult(s -> paymentRepo.lockByOrderId(payment.getOrderId())
                .filter(p -> p.getStatus() == PaymentStatus.PAID)
                .ifPresent(p -> p.markRefunded(refundId, Instant.now())));

        log.warn("Refunded {} paise for order {} ({})", payment.getAmountPaise(),
                payment.getOrderId(), request.reason());
        return RefundOutcome.REFUNDED;
    }

    /**
     * Two ways in, because the two things that ask for refunds know different
     * things.
     *
     * A late payment names the ORDER, because it came from a payment event that
     * carried one. A cancellation only knows whose booking it was — booking-service
     * never stored an order id, and should not have to. This service already keeps
     * one payment per (user, booking), so that pair is enough.
     *
     * The amount always comes from OUR record of what was taken, never from the
     * request. Refunding a number somebody else sent us is how you refund the
     * wrong amount.
     */
    private Optional<Payment> find(RefundRequest request) {
        if (request.orderId() != null && !request.orderId().isBlank()) {
            return paymentRepo.findByOrderId(request.orderId());
        }
        return paymentRepo.findByUserIdAndRequestId(request.userId(), request.requestId());
    }
}
