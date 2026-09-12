package com.middleberth.booking.dto;

import java.time.LocalDate;

/**
 * What notification-service is told. Written into the outbox at the moment it
 * becomes true, so the mail says what was true then, not what is true whenever
 * the mail finally goes out.
 *
 * berth is null on a cancellation, reason is null on a ticket.
 */
public record NotificationEvent(NotificationType type,
                                Long userId,
                                String requestId,
                                String trainNumber,
                                LocalDate travelDate,
                                String coachClass,
                                String berth,
                                String reason) {

    public static NotificationEvent ticket(Long userId, String requestId, String trainNumber,
                                           LocalDate travelDate, String coachClass, String berth) {
        return new NotificationEvent(NotificationType.TICKET_CONFIRMED, userId, requestId,
                trainNumber, travelDate, coachClass, berth, null);
    }

    public static NotificationEvent cancelled(Long userId, String requestId, String trainNumber,
                                              LocalDate travelDate, String coachClass, String reason) {
        return new NotificationEvent(NotificationType.BOOKING_CANCELLED, userId, requestId,
                trainNumber, travelDate, coachClass, null, reason);
    }
}
