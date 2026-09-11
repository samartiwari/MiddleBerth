package com.middleberth.booking.kafka;

import com.middleberth.booking.dto.SeatCountEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Tells search-service how many berths are left, so the availability page never
 * has to ask booking-service's database.
 */
@Component
@RequiredArgsConstructor
public class SeatCountPublisher {

    private final KafkaTemplate<String, Object> kafka;

    public void publish(SeatCountEvent event) {
        String key = event.trainNumber() + "|" + event.travelDate() + "|" + event.coachClass();
        kafka.send(KafkaTopicConfig.SEAT_COUNTS, key, event);
    }
}
