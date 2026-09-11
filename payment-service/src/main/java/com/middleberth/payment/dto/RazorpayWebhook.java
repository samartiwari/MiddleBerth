package com.middleberth.payment.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * The parts of Razorpay's webhook this service reads. The real payload has many
 * more fields; they are ignored rather than modelled.
 *
 *   { "event": "payment.captured",
 *     "payload": { "payment": { "entity": {
 *         "id": "pay_...", "order_id": "order_...", "amount": 240000,
 *         "currency": "INR", "status": "captured", "created_at": 1756108800 } } } }
 *
 * amount is paise. created_at is unix seconds. Checked against Razorpay's
 * documentation so the stub and the real thing agree.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RazorpayWebhook(String event, Payload payload) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Payload(PaymentWrapper payment) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PaymentWrapper(Entity entity) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Entity(String id,
                         @JsonProperty("order_id") String orderId,
                         long amount,
                         String currency,
                         String status,
                         @JsonProperty("created_at") long createdAt) {
    }
}
