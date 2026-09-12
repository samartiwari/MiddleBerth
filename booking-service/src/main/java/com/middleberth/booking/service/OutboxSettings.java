package com.middleberth.booking.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "middleberth.outbox")
public record OutboxSettings(Duration interval, int batchSize) {
}
