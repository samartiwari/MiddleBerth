package com.middleberth.booking.kafka;

import com.middleberth.booking.dto.BookingCommand;
import com.middleberth.booking.service.IntakeSettings;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@Component
@RequiredArgsConstructor
public class BookingPublisher {

    private final KafkaTemplate<String, BookingCommand> kafka;
    private final IntakeSettings intake;

    /**
     * The key is what makes the whole thing work: hash(key) % partitions decides
     * the partition, so every request for one train, date and class lands in the
     * same partition — and therefore is handled by one consumer, one at a time,
     * in arrival order.
     *
     * That gives fairness (first in the queue wins) and keeps contention for one
     * train out of every other train's way.
     */
    /**
     * Puts the request on the queue and reports whether the broker took it.
     *
     * This used to be fire-and-forget: hand the message to the producer and reply
     * 202 at once. That was the last place the system could lose someone's work —
     * the user had a request id, believed they were in the queue, and nothing was
     * ever going to happen. No error, no row, no message, nobody any the wiser.
     *
     * So the 202 now waits for the broker's acknowledgement. It does NOT wait on a
     * request thread: the controller hands this future back to Spring, the thread
     * goes off and serves someone else, and the reply is written when the broker
     * answers.
     *
     * That distinction is not academic. Blocking the thread instead was measured on
     * a 2,000 person spike: intake p95 went from 794ms to 1.82s, and because
     * admission was limited by the size of the thread pool, the time from click to
     * answer doubled to 20s.
     *
     * Retrying after a failure cannot double-book: the UNIQUE constraint on
     * (user_id, request_id) decides that, not this.
     *
     * Not an outbox, deliberately. An outbox means a database write at intake,
     * which is the one thing the front door is supposed to avoid.
     */
    public CompletableFuture<Void> publish(BookingCommand command) {
        String key = keyFor(command);
        return kafka.send(KafkaTopicConfig.BOOKING_REQUESTS, key, command)
                .orTimeout(intake.ackTimeout().toMillis(), TimeUnit.MILLISECONDS)
                .thenAccept(sent -> { })
                .exceptionally(e -> {
                    throw new QueueUnavailableException(key, e);
                });
    }

    static String keyFor(BookingCommand c) {
        return c.trainNumber() + "|" + c.travelDate() + "|" + c.coachClass();
    }
}
