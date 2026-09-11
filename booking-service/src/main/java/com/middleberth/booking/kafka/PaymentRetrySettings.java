package com.middleberth.booking.kafka;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * How hard to try before giving up on a payment event.
 *
 * Long enough to ride out a database blip or a restart — a couple of minutes —
 * so a customer's payment is not dropped because Postgres was busy for two
 * seconds. Tests shrink these so they do not wait minutes.
 */
@ConfigurationProperties(prefix = "middleberth.payment-events.retry")
public record PaymentRetrySettings(Duration initialInterval, Duration maxInterval, int maxAttempts) {
}
