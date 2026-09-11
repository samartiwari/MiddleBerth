package com.middleberth.search.kafka;

import com.middleberth.search.cache.SeatCountCache;
import com.middleberth.search.dto.SeatCountEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Keeps Redis up to date with what booking-service says is left.
 *
 * Its own group id, so this service gets every event independently of
 * booking-service — that is fan-out, and it is why search can be added, removed
 * or replayed without booking-service knowing or caring.
 *
 * The event carries the absolute count, so a duplicate or an out-of-order
 * delivery is harmless: the newest write wins and the next event corrects
 * anything stale.
 */
@Component
@RequiredArgsConstructor
public class SeatCountConsumer {

    static final String TOPIC = "seat-counts";

    private final SeatCountCache cache;

    @KafkaListener(topics = TOPIC, groupId = "search-service")
    public void handle(SeatCountEvent event) {
        cache.put(event.trainNumber(), event.travelDate(), event.coachClass(), event.freeSeats());
    }
}
