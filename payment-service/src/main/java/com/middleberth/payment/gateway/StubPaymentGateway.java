package com.middleberth.payment.gateway;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A fake Razorpay. Hands out order ids that look like Razorpay's and never
 * touches the network.
 *
 * The other half of "being Razorpay" — sending webhooks — is played by the tests
 * themselves: they build a payload, sign it with the webhook secret exactly the way
 * Razorpay does, and POST it. That is also how the real webhook handling gets
 * tested, since the signature check does not care who signed it.
 */
@Component
@ConditionalOnProperty(name = "middleberth.razorpay.mode", havingValue = "stub", matchIfMissing = true)
public class StubPaymentGateway implements PaymentGateway {

    @Override
    public GatewayOrder createOrder(long amountPaise, String currency, String receipt) {
        String id = "order_stub" + UUID.randomUUID().toString().replace("-", "").substring(0, 14);
        return new GatewayOrder(id, amountPaise, currency);
    }

    @Override
    public String publicKeyId() {
        return "rzp_test_stub";
    }

    // ---------- refunds ----------

    private final Map<String, String> refundByPayment = new ConcurrentHashMap<>();
    private final AtomicInteger refundsPaidOut = new AtomicInteger();

    /** Safe to repeat: a payment already refunded gets its existing refund id back. */
    @Override
    public String refundInFull(String paymentId, long amountPaise) {
        return refundByPayment.computeIfAbsent(paymentId, id -> {
            refundsPaidOut.incrementAndGet();          // money actually leaves only here
            return "rfnd_stub" + UUID.randomUUID().toString().replace("-", "").substring(0, 14);
        });
    }

    /** Test hook: how many refunds actually paid money out. */
    public int refundsPaidOut() {
        return refundsPaidOut.get();
    }

    // ---------- payments whose webhook never arrived ----------

    private final Map<String, CapturedPayment> capturedByOrder = new ConcurrentHashMap<>();

    @Override
    public Optional<CapturedPayment> findCapturedPayment(String orderId) {
        return Optional.ofNullable(capturedByOrder.get(orderId));
    }

    /**
     * Test hook: the customer paid, Razorpay has the money, and the webhook got
     * lost on the way. Only the reconciliation job can find it now.
     */
    public void simulatePaidButWebhookLost(String orderId, String paymentId, long amountPaise, long createdAt) {
        capturedByOrder.put(orderId, new CapturedPayment(paymentId, amountPaise, createdAt));
    }

    public void reset() {
        refundByPayment.clear();
        refundsPaidOut.set(0);
        capturedByOrder.clear();
    }
}
