package com.middleberth.booking.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.middleberth.booking.domain.Booking;
import com.middleberth.booking.domain.OutboxNote;
import com.middleberth.booking.dto.NotificationEvent;
import com.middleberth.booking.repository.OutboxRepository;
import com.middleberth.booking.repository.TrainRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Writes the note that says "tell this passenger something".
 *
 * Deliberately NOT @Transactional. Every method here is called from inside a
 * transaction that is already saving the booking, and it must join that one. If
 * it started its own, the note could be saved while the booking rolled back —
 * which is the whole problem the outbox exists to remove.
 */
@Component
@RequiredArgsConstructor
public class Outbox {

    private final OutboxRepository outboxRepo;
    private final TrainRepository trainRepo;
    private final ObjectMapper json;

    public void ticketConfirmed(Booking booking, String berth) {
        write(booking, NotificationEvent.ticket(booking.getUserId(), booking.getRequestId(),
                trainNumber(booking), booking.getTravelDate(), booking.getCoachClass(), berth,
                nameOf(booking), emailOf(booking), booking.getPnr()));
    }

    public void bookingCancelled(Booking booking, String reason) {
        write(booking, NotificationEvent.cancelled(booking.getUserId(), booking.getRequestId(),
                trainNumber(booking), booking.getTravelDate(), booking.getCoachClass(), reason,
                nameOf(booking), emailOf(booking), booking.getPnr()));
    }

    private String nameOf(Booking booking) {
        return booking.getPassenger() == null ? null : booking.getPassenger().getName();
    }

    /**
     * Taken from the booking, so notification-service has nothing to look up and
     * no table of its own to keep in step with this one.
     */
    private String emailOf(Booking booking) {
        return booking.getPassenger() == null ? null : booking.getPassenger().getEmail();
    }

    /**
     * The note carries the finished message, not a pointer to one. Reading the
     * booking again at sending time would describe it as it is then — and by then
     * a cancelled booking has moved on from what the mail is supposed to say.
     */
    private void write(Booking booking, NotificationEvent event) {
        String key = booking.getUserId() + "|" + booking.getRequestId();
        try {
            outboxRepo.save(new OutboxNote(key, event.type().name(), json.writeValueAsString(event)));
        } catch (Exception e) {
            throw new IllegalStateException("Could not write the outbox note for " + key, e);
        }
    }

    private String trainNumber(Booking booking) {
        return trainRepo.findById(booking.getTrainId()).orElseThrow().getNumber();
    }
}
