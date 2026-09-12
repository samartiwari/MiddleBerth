package com.middleberth.booking.kafka;

import com.middleberth.booking.dto.NotificationEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
@RequiredArgsConstructor
public class BookingEventPublisher {

    private final KafkaTemplate<String, Object> kafka;

    /**
     * Waits for the broker and throws if it cannot get through. The note stays in
     * the outbox with sent_at still empty, and the job picks it up again next time.
     * Nothing is lost by failing here.
     */
    public void publishAndWait(String key, NotificationEvent event) {
        try {
            kafka.send(KafkaTopicConfig.BOOKING_EVENTS, key, event).get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("Could not publish the booking event for " + key, e);
        }
    }
}
