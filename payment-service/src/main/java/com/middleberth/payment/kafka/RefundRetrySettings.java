package com.middleberth.payment.kafka;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** How hard to try a refund before parking it on the dead letter topic. */
@ConfigurationProperties(prefix = "middleberth.refund-requests.retry")
public record RefundRetrySettings(Duration initialInterval, Duration maxInterval, int maxAttempts) {
}
