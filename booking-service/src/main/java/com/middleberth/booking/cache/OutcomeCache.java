package com.middleberth.booking.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.middleberth.booking.dto.BookingResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

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
 * It also knows which bookings are still waiting. The front door notes a request
 * as pending when it queues it, and the booking thread overwrites that note with
 * the answer the moment it has one. Before that, a poll about a booking nobody had
 * decided yet found nothing here and asked Postgres, which had nothing to say
 * either — thousands of times a second once the booking threads fell behind.
 *
 * Redis being down is not an error here. A miss reads Postgres, which is where
 * the truth was all along.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OutcomeCache {

    /** What Redis knows about one booking. */
    public sealed interface Lookup permits Answer, Pending, Unknown {
    }

    /** Decided, and this is what they got. */
    public record Answer(BookingResult result) implements Lookup {
    }

    /** Accepted at the door at this moment, and not decided yet. */
    public record Pending(Instant since) implements Lookup {
    }

    /** Nothing here: never queued, expired, or Redis could not be reached. */
    public record Unknown() implements Lookup {
    }

    // Not JSON, on purpose. Spring's ObjectMapper ignores fields it does not know,
    // so a JSON "pending" note would read back as a booking with no status at all.
    private static final String PENDING = "pending:";

    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final CacheSettings settings;

    public Lookup lookup(Long userId, String requestId) {
        try {
            String cached = redis.opsForValue().get(key(userId, requestId));
            if (cached == null) {
                return new Unknown();
            }
            if (cached.startsWith(PENDING)) {
                return new Pending(Instant.ofEpochMilli(Long.parseLong(cached.substring(PENDING.length()))));
            }
            return new Answer(json.readValue(cached, BookingResult.class));
        } catch (Exception e) {
            log.debug("Outcome cache unavailable, falling back to the database: {}", e.getMessage());
            return new Unknown();
        }
    }

    public void put(Long userId, String requestId, BookingResult result) {
        write(userId, requestId, result, settings.outcomeTtl());
    }

    /**
     * The one answer that lives ONLY here.
     *
     * A regret holds no berth, no waitlist number and no money, so it is not
     * written to the database at all — there would be nothing in the row but the
     * word "no". Redis carries it instead, and for much longer than the few
     * seconds an ordinary cached answer gets: a regret is final, so unlike a hold
     * it cannot become wrong while it sits here.
     */
    public void putRegret(Long userId, String requestId) {
        write(userId, requestId, BookingResult.regretted(), settings.regretTtl());
    }

    /**
     * Notes that a request was accepted at the door and is waiting for a booking
     * thread.
     *
     * Only if nothing is there already. The booking thread can finish before the
     * front door gets round to this, and a note saying "waiting" must never replace
     * the answer it was waiting for. The answer, when it comes, simply overwrites it.
     */
    public void markPending(Long userId, String requestId, Instant since) {
        try {
            redis.opsForValue().setIfAbsent(key(userId, requestId), PENDING + since.toEpochMilli(),
                    settings.pendingTtl());
        } catch (Exception e) {
            log.debug("Could not note the request as pending: {}", e.getMessage());
        }
    }

    private void write(Long userId, String requestId, BookingResult result, Duration ttl) {
        try {
            redis.opsForValue().set(key(userId, requestId), json.writeValueAsString(result), ttl);
        } catch (Exception e) {
            log.debug("Could not cache the outcome: {}", e.getMessage());
        }
    }

    private static String key(Long userId, String requestId) {
        return "middleberth:outcome:" + userId + "|" + requestId;
    }
}
