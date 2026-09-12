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

    /**
     * The PNR is what a passenger quotes to anybody — at a counter, to a ticket
     * examiner. A booking nobody paid for never got one, so the request id stands
     * in there.
     */
    private static String reference(NotificationEvent event) {
        return event.pnr() == null || event.pnr().isBlank() ? event.requestId() : event.pnr();
    }

    public Mail write(NotificationEvent event, String to) {
        return switch (event.type()) {
            case TICKET_CONFIRMED -> new Mail(to,
                    "Ticket confirmed — train %s on %s".formatted(event.trainNumber(), event.travelDate()),
                    """
                    Dear %s,

                    Your berth is %s (%s).

                    Train %s, %s.
                    PNR %s.

                    Happy journey.""".formatted(greeting(event), event.berth(), event.coachClass(),
                            event.trainNumber(), event.travelDate(), reference(event)));

            case BOOKING_CANCELLED -> new Mail(to,
                    "Booking cancelled — train %s on %s".formatted(event.trainNumber(), event.travelDate()),
                    """
                    Dear %s,

                    Your booking %s for train %s on %s is cancelled.

                    Any money taken is on its way back to the account you paid from.
                    Bank refunds usually take 5 to 7 working days.""".formatted(greeting(event),
                            reference(event), event.trainNumber(), event.travelDate()));
        };
    }
}
