package com.middleberth.booking.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * How long a hold lasts.
 *
 * payWithin is what the user is told. The hold is actually released a cushion
 * after that — so a payment made at 4:59 is not lost to a few seconds of our own
 * processing lag. From initial.md: judge by when they paid, not when we found out.
 */
@ConfigurationProperties(prefix = "middleberth.hold")
public record HoldSettings(Duration payWithin, Duration cushion, Duration expiryInterval, int batchSize) {
}
