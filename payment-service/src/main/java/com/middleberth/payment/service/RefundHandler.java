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
 * refund is safe to repeat. A crash between 2 and 3 means this runs again,
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

        long amount = amountToReturn(payment, request);
        String refundId = gateway.refund(payment.getRazorpayPaymentId(), amount);

        tx.executeWithoutResult(s -> paymentRepo.lockByOrderId(payment.getOrderId())
                .filter(p -> p.getStatus() == PaymentStatus.PAID)
                .ifPresent(p -> p.markRefunded(refundId, Instant.now())));

        log.warn("Refunded {} of {} paise for order {} ({})", amount, payment.getAmountPaise(),
                payment.getOrderId(), request.reason());
        return RefundOutcome.REFUNDED;
    }

    /**
     * How much goes back, and it depends entirely on WHOSE fault it was.
     *
     * A passenger who changes their mind gets the ticket price back, not the
     * convenience fee. That fee has already been spent — the gateway took its cut
     * on the way in and does not return it — so refunding it would mean paying it
     * out of money that was never received. IRCTC does exactly this, for exactly
     * this reason.
     *
     * Everything else here is OUR failure: money taken for a berth we could not
     * give, a payment that reached us too late to honour. Keeping a fee for a
     * service never delivered would be indefensible, so those get all of it —
     * including the part the gateway kept, which the company then swallows.
     */
    private static long amountToReturn(Payment payment, RefundRequest request) {
        return "CANCELLED_BY_PASSENGER".equals(request.reason())
                ? payment.getRefundablePaise()
                : payment.getAmountPaise();
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
