package com.middleberth.booking.cache;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * How stale a polled answer may be.
 *
 * outcomeTtl — a few seconds, because the page asks again a second later anyway.
 * Long enough to take almost every poll off the database, short enough that
 * nobody watches a wrong answer for long.
 *
 * regretTtl — much longer, because a regret is not a cached copy of anything. It
 * is the whole record, kept nowhere else, and it is final: no berth, no number,
 * no money, nothing that can change underneath it. Minutes, not seconds, so the
 * page has every chance to see it before it goes.
 */
@ConfigurationProperties(prefix = "middleberth.cache")
public record CacheSettings(Duration outcomeTtl, Duration regretTtl) {
}
