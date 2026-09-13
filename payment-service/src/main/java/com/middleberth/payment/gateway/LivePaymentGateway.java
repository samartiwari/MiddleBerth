package com.middleberth.payment.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.net.http.HttpClient;
import java.util.Map;
import java.util.Optional;

/**
 * The real Razorpay.
 *
 * Turned on with middleberth.razorpay.mode=live. Everything else — tests, load
 * tests, the smoke test — keeps the stub, because Razorpay's own API is rate
 * limited and a load test pointed at it measures them, not us.
 *
 * Three calls, and each one is here because something in this system needs it:
 *
 *   create an order          Pay Now, so the customer has something to pay
 *   find payments for order  the reconciliation job, for webhooks that never came
 *   refund in full           late money we cannot honour
 *
 * Responses are read as JSON trees rather than mapped into records. These payloads
 * have dozens of fields, of which we want three or four, and a new field appearing
 * in one of them must never break a refund.
 */
@Component
@ConditionalOnProperty(name = "middleberth.razorpay.mode", havingValue = "live")
@Slf4j
public class LivePaymentGateway implements PaymentGateway {

    private final RazorpaySettings settings;
    private final RestClient razorpay;

    public LivePaymentGateway(RazorpaySettings settings) {
        this.settings = settings;

        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(settings.connectTimeout())
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(settings.readTimeout());

        this.razorpay = RestClient.builder()
                .baseUrl(settings.apiUrl())
                .requestFactory(factory)
                // Razorpay authenticates with the key id and secret as HTTP Basic.
                .defaultHeaders(headers -> headers.setBasicAuth(settings.keyId(), settings.keySecret()))
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    /**
     * Fail at startup, not at the first payment. Coming up healthy without keys
     * and then refusing every customer is the worse outcome.
     */
    @PostConstruct
    void checkConfigured() {
        if (isBlank(settings.keyId()) || isBlank(settings.keySecret())) {
            throw new IllegalStateException("middleberth.razorpay.mode=live needs RAZORPAY_KEY_ID "
                    + "and RAZORPAY_KEY_SECRET in the environment");
        }
        log.info("Razorpay LIVE mode, key {}", settings.keyId());
    }

    @Override
    public GatewayOrder createOrder(long amountPaise, String currency, String receipt) {
        // amount is in paise, as an integer. receipt is ours, 40 characters at most.
        JsonNode order = post("/orders", Map.of(
                "amount", amountPaise,
                "currency", currency,
                "receipt", receipt), null);

        return new GatewayOrder(order.get("id").asText(), order.get("amount").asLong(),
                order.get("currency").asText());
    }

    @Override
    public String publicKeyId() {
        return settings.keyId();
    }

    /**
     * What the reconciliation job asks when a webhook never arrived.
     *
     * The response holds every payment attempt against the order, including failed
     * ones. Only a captured payment is money we actually have.
     */
    @Override
    public Optional<CapturedPayment> findCapturedPayment(String orderId) {
        JsonNode payments = get("/orders/" + orderId + "/payments");
        for (JsonNode payment : payments.path("items")) {
            if ("captured".equals(payment.path("status").asText())) {
                return Optional.of(new CapturedPayment(
                        payment.get("id").asText(),
                        payment.get("amount").asLong(),
                        payment.get("created_at").asLong()));
            }
        }
        return Optional.empty();
    }

    /**
     * Safe to call twice, which the rest of the system depends on: a refund request
     * is retried until it is recorded, and a crash between refunding and writing it
     * down means asking again.
     *
     * Two defences, because paying somebody twice is not recoverable by an apology.
     *
     *   1. ask what refunds this payment already has, and reuse one
     *   2. send an idempotency key derived from the payment id, so the SAME key
     *      arrives every time and Razorpay itself refuses the duplicate
     *
     * The first covers the case where the second no longer applies — an
     * idempotency key is only remembered for a limited time, and a refund parked
     * on a dead letter topic might be retried by a human days later.
     */
    @Override
    public String refund(String paymentId, long amountPaise) {
        Optional<String> already = existingRefund(paymentId, amountPaise);
        if (already.isPresent()) {
            log.info("Payment {} was already refunded as {}", paymentId, already.get());
            return already.get();
        }

        JsonNode refund = post("/payments/" + paymentId + "/refund",
                Map.of("amount", amountPaise),
                "refund-" + paymentId);          // at least 10 characters, and always the same
        return refund.get("id").asText();
    }

    private Optional<String> existingRefund(String paymentId, long amountPaise) {
        JsonNode refunds = get("/payments/" + paymentId + "/refunds");
        for (JsonNode refund : refunds.path("items")) {
            if (refund.path("amount").asLong() == amountPaise) {
                return Optional.of(refund.get("id").asText());
            }
        }
        return Optional.empty();
    }

    // ---------- the two shapes of call ----------

    private JsonNode get(String path) {
        try {
            return razorpay.get().uri(path).retrieve().body(JsonNode.class);
        } catch (RestClientException e) {
            throw new RazorpayException("Razorpay GET " + path + " failed" + detailOf(e), e);
        }
    }

    private JsonNode post(String path, Map<String, Object> body, String idempotencyKey) {
        try {
            var request = razorpay.post().uri(path);
            if (idempotencyKey != null) {
                request = request.header("X-Refund-Idempotency", idempotencyKey);
            }
            return request.body(body).retrieve().body(JsonNode.class);
        } catch (RestClientException e) {
            throw new RazorpayException("Razorpay POST " + path + " failed" + detailOf(e), e);
        }
    }

    /**
     * What Razorpay actually said.
     *
     * Without this a refund that will not go through reads only as "POST ...
     * failed", which is exactly as useful as silence — and a refund is the one
     * failure somebody has to fix by hand, from a log, possibly days later. The
     * status code and their description turn a guess into an instruction.
     *
     * Their error body is safe to repeat. It describes our REQUEST back to us and
     * never contains the key we authenticated with, which is why this appends the
     * response and never the request.
     */
    private static String detailOf(RestClientException e) {
        if (e instanceof RestClientResponseException http) {
            String body = http.getResponseBodyAsString();
            return " — HTTP " + http.getStatusCode().value()
                    + (body.isBlank() ? "" : " " + body.substring(0, Math.min(body.length(), 400)));
        }
        return e.getMessage() == null ? "" : " — " + e.getMessage();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
