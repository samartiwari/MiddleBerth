package com.middleberth.payment.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How long a finished payment is kept. A week, matching the booking it belongs
 * to — booking-service deletes travel dates older than that, so nothing can ask
 * about these afterwards.
 */
@ConfigurationProperties(prefix = "middleberth.purge")
public record PurgeSettings(int keepDays, String zone) {
}
