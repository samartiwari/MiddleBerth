package com.middleberth.booking.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * How long the front door waits for Kafka to confirm a booking request before
 * giving up and telling the user to try again.
 *
 * Short on purpose. A request thread waiting here is a thread not serving anyone,
 * and at 10:00:00 there are thousands of them.
 */
@ConfigurationProperties(prefix = "middleberth.intake")
public record IntakeSettings(Duration ackTimeout) {
}
