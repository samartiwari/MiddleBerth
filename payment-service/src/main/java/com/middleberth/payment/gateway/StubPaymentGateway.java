package com.middleberth.payment.gateway;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.UUID;

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
}
