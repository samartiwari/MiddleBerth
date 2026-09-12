package com.middleberth.notification.send;

import com.middleberth.notification.dto.NotificationEvent;
import org.springframework.stereotype.Component;

/** Turns an event into words. */
@Component
public class MailWriter {

    private static String greeting(NotificationEvent event) {
        return event.passengerName() == null || event.passengerName().isBlank()
                ? "passenger" : event.passengerName();
    }

    public Mail write(NotificationEvent event, String to) {
        return switch (event.type()) {
            case TICKET_CONFIRMED -> new Mail(to,
                    "Ticket confirmed — train %s on %s".formatted(event.trainNumber(), event.travelDate()),
                    """
                    Dear %s,

                    Your berth is %s (%s).

                    Train %s, %s.
                    Booking reference %s.

                    Happy journey.""".formatted(greeting(event), event.berth(), event.coachClass(),
                            event.trainNumber(), event.travelDate(), event.requestId()));

            case BOOKING_CANCELLED -> new Mail(to,
                    "Booking cancelled — train %s on %s".formatted(event.trainNumber(), event.travelDate()),
                    """
                    Dear %s,

                    Your booking %s for train %s on %s could not be confirmed.

                    Any money taken is on its way back to the account you paid from.
                    Bank refunds usually take 5 to 7 working days.""".formatted(greeting(event),
                            event.requestId(), event.trainNumber(), event.travelDate()));
        };
    }
}
