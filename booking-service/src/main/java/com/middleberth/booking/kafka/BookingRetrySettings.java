package com.middleberth.booking.kafka;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "middleberth.booking-requests.retry")
public record BookingRetrySettings(Duration initialInterval, Duration maxInterval, int maxAttempts) {
}
