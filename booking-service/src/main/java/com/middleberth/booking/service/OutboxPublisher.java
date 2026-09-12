package com.middleberth.booking.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.middleberth.booking.domain.OutboxNote;
import com.middleberth.booking.dto.NotificationEvent;
import com.middleberth.booking.kafka.BookingEventPublisher;
import com.middleberth.booking.repository.OutboxRepository;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Takes notes out of the outbox and puts them on Kafka.
 *
 * Its own class so @Transactional goes through the Spring proxy — same rule as
 * HoldReleaser and the rest.
 */
@Service
@RequiredArgsConstructor
public class OutboxPublisher {

    private final OutboxRepository outboxRepo;
    private final BookingEventPublisher publisher;
    private final ObjectMapper json;
    private final OutboxSettings outbox;

    /**
     * One batch, one transaction: lock the notes, send them, mark them sent.
     *
     * The send happens with the rows still locked. That is the one place in this
     * project where a database lock is held across a network call, and it is
     * deliberate: the broker is ours and answers in milliseconds, and the lock is
     * exactly what stops a second pod sending the same mail. A third-party call,
     * like Razorpay, would not be allowed here.
     *
     * If the send works but the commit does not, sent_at never lands and the note
     * goes out a second time later. That is the safe direction — a passenger can
     * get the same mail twice, but never no mail at all. notification-service
     * throws the duplicate away.
     */
    @Transactional
    public int publishBatch() {
        List<OutboxNote> notes = outboxRepo.lockUnsent(outbox.batchSize());
        Instant now = Instant.now();
        for (OutboxNote note : notes) {
            publisher.publishAndWait(note.getBookingKey(), read(note));
            note.markSent(now);
        }
        return notes.size();
    }

    /** The row is the message. It is read back rather than rebuilt, so what was written is what goes out. */
    @SneakyThrows
    private NotificationEvent read(OutboxNote note) {
        return json.readValue(note.getPayload(), NotificationEvent.class);
    }
}
