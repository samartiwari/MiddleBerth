package com.middleberth.booking.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.middleberth.booking.dto.BookingResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/**
 * What a waiting page polls for, kept out of the database.
 *
 * A booking is answered once and then asked about again and again — every second,
 * by everyone still waiting. Each of those used to be a query against the same
 * Postgres the consumer needs for claiming berths: about fifty thousand of them
 * in one two-minute load run, all competing with the actual work.
 *
 * The cache is deliberately dumb: a short time to live, and no invalidation
 * anywhere. Nothing has to remember to clear it when a booking is paid for,
 * expires, or is cancelled — which is exactly the kind of thing that gets
 * forgotten in one of five places and shows somebody a stale answer for ever.
 * The cost is that an answer can be a few seconds out of date, on a page that
 * polls every second anyway.
 *
 * Redis being down is not an error here. A miss reads Postgres, which is where
 * the truth was all along.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OutcomeCache {

    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final CacheSettings settings;

    public Optional<BookingResult> get(Long userId, String requestId) {
        try {
            String cached = redis.opsForValue().get(key(userId, requestId));
            return cached == null ? Optional.empty()
                    : Optional.of(json.readValue(cached, BookingResult.class));
        } catch (Exception e) {
            log.debug("Outcome cache unavailable, falling back to the database: {}", e.getMessage());
            return Optional.empty();
        }
    }

    public void put(Long userId, String requestId, BookingResult result) {
        try {
            redis.opsForValue().set(key(userId, requestId), json.writeValueAsString(result),
                    Duration.ofMillis(settings.outcomeTtl().toMillis()));
        } catch (Exception e) {
            log.debug("Could not cache the outcome: {}", e.getMessage());
        }
    }

    private static String key(Long userId, String requestId) {
        return "middleberth:outcome:" + userId + "|" + requestId;
    }
}
