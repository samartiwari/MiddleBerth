package com.middleberth.notification.kafka;

import com.middleberth.notification.dto.NotificationEvent;
import com.middleberth.notification.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class BookingEventConsumer {

    private final NotificationService notifications;

    @KafkaListener(topics = BookingEventsConfig.BOOKING_EVENTS,
                   groupId = "notification-service",
                   containerFactory = "bookingEventsFactory")
    public void handle(NotificationEvent event) {
        notifications.handle(event);
    }
}
