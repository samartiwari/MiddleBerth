package com.middleberth.booking.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The booking window.
 *
 *   daysAhead  how far in front of today a date opens. 1 is tatkal: tomorrow's
 *              quota goes on sale today.
 *   keepDays   how much of the past to keep — a week. Travel dates older than
 *              that are deleted, which is what stops the database growing for
 *              ever. The window is then about nine days of data, for good.
 *   zone       India, not the server's timezone. A container runs on UTC and
 *              "noon" has to mean noon where the passengers are.
 */
@ConfigurationProperties(prefix = "middleberth.quota")
public record QuotaSettings(int daysAhead, int keepDays, String zone, boolean openOnStartup) {
}
