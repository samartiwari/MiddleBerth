package com.middleberth.booking.cache;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * How stale a polled answer may be.
 *
 * A few seconds, because the page asks again a second later anyway. Long enough
 * to take almost every poll off the database, short enough that nobody watches a
 * wrong answer for long.
 */
@ConfigurationProperties(prefix = "middleberth.cache")
public record CacheSettings(Duration outcomeTtl) {
}
