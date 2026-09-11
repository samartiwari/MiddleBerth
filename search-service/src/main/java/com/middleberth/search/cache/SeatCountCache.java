package com.middleberth.search.cache;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;
import java.util.OptionalInt;

/**
 * How many berths are left, kept in Redis.
 *
 * One plain string per train, date and class. Written by the Kafka consumer,
 * read by the availability endpoint. Nothing clever — it is one number that gets
 * overwritten.
 *
 * Losing this is survivable: the next event from booking-service repopulates it.
 * Until then the endpoint says UNKNOWN rather than guessing.
 */
@Component
@RequiredArgsConstructor
public class SeatCountCache {

    /** Long enough to outlive a booking window, short enough that old dates expire. */
    private static final Duration TTL = Duration.ofDays(7);

    private final StringRedisTemplate redis;

    public void put(String trainNumber, LocalDate travelDate, String coachClass, int freeSeats) {
        redis.opsForValue().set(key(trainNumber, travelDate, coachClass),
                String.valueOf(freeSeats), TTL);
    }

    public OptionalInt get(String trainNumber, LocalDate travelDate, String coachClass) {
        String value = redis.opsForValue().get(key(trainNumber, travelDate, coachClass));
        return value == null ? OptionalInt.empty() : OptionalInt.of(Integer.parseInt(value));
    }

    /** seats:12951:2026-08-25:3A */
    static String key(String trainNumber, LocalDate travelDate, String coachClass) {
        return "seats:" + trainNumber + ":" + travelDate + ":" + coachClass;
    }
}
