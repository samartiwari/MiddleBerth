package com.middleberth.notification.kafka;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "middleberth.booking-events.retry")
public record BookingEventsRetrySettings(Duration initialInterval, Duration maxInterval, int maxAttempts) {
}
