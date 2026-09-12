package com.middleberth.payment;

import com.middleberth.payment.gateway.LivePaymentGateway;
import com.middleberth.payment.gateway.PaymentGateway;
import com.middleberth.payment.gateway.RazorpaySettings;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The live Razorpay client, against a fake Razorpay on localhost.
 *
 * No Spring context and no network: this is about whether the client sends what
 * Razorpay's documentation says to send, and reads back what Razorpay actually
 * returns. That is where a payment client goes wrong — the wrong field name, the
 * amount in rupees instead of paise, a missing idempotency key.
 *
 * What this cannot prove is that Razorpay behaves as documented. Nothing short of
 * their test keys can, and that is stated in the notes rather than glossed over.
 */
class LivePaymentGatewayTest {

    private static FakeRazorpay razorpay;
    private static PaymentGateway gateway;

    @BeforeAll
    static void start() {
        razorpay = FakeRazorpay.start();
        gateway = new LivePaymentGateway(new RazorpaySettings(
                "rzp_test_fake", "secret_fake", razorpay.url(),
                Duration.ofSeconds(2), Duration.ofSeconds(5)));
    }

    @AfterAll
    static void stop() {
        razorpay.reset();
    }

    @BeforeEach
    void fresh() {
        razorpay.reset();
    }

    @Test
    void an_order_is_created_in_paise_and_authenticated_with_the_key() {
        var order = gateway.createOrder(240000, "INR", "5512|A7X2");

        assertThat(order.orderId()).isEqualTo("order_LiveTest123");
        assertThat(order.amountPaise()).isEqualTo(240000);

        var sent = razorpay.seen().get(0);
        assertThat(sent.method()).isEqualTo("POST");
        assertThat(sent.path()).isEqualTo("/orders");
        assertThat(sent.body())
                .as("paise, not rupees, and our own receipt")
                .contains("\"amount\":240000")
                .contains("\"currency\":\"INR\"")
                .contains("\"receipt\":\"5512|A7X2\"");

        String expected = "Basic " + Base64.getEncoder()
                .encodeToString("rzp_test_fake:secret_fake".getBytes());
        assertThat(sent.auth()).as("key id and secret, as HTTP Basic").isEqualTo(expected);
    }

    /**
     * What the reconciliation job asks for when a webhook never arrived. The order
     * has a failed attempt as well, and failed money is not money.
     */
    @Test
    void only_a_captured_payment_counts_as_paid() {
        var captured = gateway.findCapturedPayment("order_LiveTest123");

        assertThat(captured).isPresent();
        assertThat(captured.get().paymentId()).as("not the failed attempt").isEqualTo("pay_LiveTest999");
        assertThat(captured.get().amountPaise()).isEqualTo(240000);
        assertThat(captured.get().createdAt()).isEqualTo(1789000002L);
    }

    @Test
    void a_refund_carries_an_idempotency_key_so_a_retry_cannot_pay_twice() {
        String refundId = gateway.refundInFull("pay_LiveTest999", 240000);

        assertThat(refundId).startsWith("rfnd_");
        var refundCall = razorpay.seen().stream()
                .filter(s -> s.method().equals("POST")).findFirst().orElseThrow();
        assertThat(refundCall.path()).isEqualTo("/payments/pay_LiveTest999/refund");
        assertThat(refundCall.body()).contains("\"amount\":240000");
        assertThat(refundCall.idempotencyKey())
                .as("the same key every time, and Razorpay requires 10 characters or more")
                .isEqualTo("refund-pay_LiveTest999")
                .hasSizeGreaterThanOrEqualTo(10);
    }

    /**
     * The idempotency key is only remembered by Razorpay for a while, and a refund
     * parked on a dead letter topic might be retried days later. So the client also
     * asks what refunds the payment already has.
     */
    @Test
    void a_payment_refunded_long_ago_is_not_refunded_again() {
        razorpay.alreadyRefunded("pay_LiveTest999");

        String refundId = gateway.refundInFull("pay_LiveTest999", 240000);

        assertThat(refundId).as("the refund it already had").isEqualTo("rfnd_FromBefore");
        assertThat(razorpay.refundsCreated()).as("no second payout").isZero();
    }
}
