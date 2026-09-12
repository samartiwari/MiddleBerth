package com.middleberth.notification.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How long the record of a sent mail is kept.
 *
 * A week, and the number is not arbitrary: this table exists to stop the same
 * mail going out twice, and a duplicate can only arrive while the message is
 * still on Kafka. Kafka keeps messages for seven days by default, so a week of
 * memory covers everything that could still turn up.
 */
@ConfigurationProperties(prefix = "middleberth.purge")
public record PurgeSettings(int keepDays, String zone) {
}
