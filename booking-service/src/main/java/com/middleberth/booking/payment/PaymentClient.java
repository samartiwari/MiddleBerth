package com.middleberth.booking.payment;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Duration;
import java.util.Map;

/**
 * The one synchronous call from one service to another in the whole system:
 * booking -> payment, when someone clicks Pay Now.
 *
 * Synchronous because the user is waiting for the checkout page — they need the
 * order back now, not in a few seconds via Kafka.
 *
 * Timeouts on purpose. Without them a stuck payment-service holds this thread
 * forever, and enough stuck threads take booking down with it. With them, a slow
 * payment-service costs at most a few seconds and turns into a clean 503.
 */
@Component
public class PaymentClient {

    private final RestClient rest;

    public PaymentClient(RestClient.Builder builder,
                         @Value("${middleberth.payment-url}") String paymentUrl) {
        SimpleClientHttpRequestFactory timeouts = new SimpleClientHttpRequestFactory();
        timeouts.setConnectTimeout(Duration.ofSeconds(2));
        timeouts.setReadTimeout(Duration.ofSeconds(5));
        this.rest = builder.baseUrl(paymentUrl).requestFactory(timeouts).build();
    }

    /**
     * amountPaise is what the customer is charged; refundablePaise is how much of
     * that is the ticket itself. They differ by the convenience fee, which is not
     * given back when a passenger cancels — see FareSettings for why that matters.
     */
    public PaymentOrder createOrder(long userId, String requestId, long amountPaise, long refundablePaise) {
        try {
            return rest.post()
                    .uri("/internal/orders")
                    .body(Map.of("userId", userId, "requestId", requestId,
                            "amountPaise", amountPaise, "refundablePaise", refundablePaise))
                    .retrieve()
                    .body(PaymentOrder.class);
        } catch (RestClientException e) {
            throw new PaymentUnavailableException(e);
        }
    }
}
