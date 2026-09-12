package com.middleberth.notification.dto;

import java.time.LocalDate;

/**
 * What booking-service says happened. This service's own copy of the shape —
 * pinned by contracts/booking-event.json, which both sides test against.
 */
public record NotificationEvent(NotificationType type,
                                Long userId,
                                String requestId,
                                String trainNumber,
                                LocalDate travelDate,
                                String coachClass,
                                String berth,
                                String reason,
                                String passengerName,
                                String passengerEmail) {
}
