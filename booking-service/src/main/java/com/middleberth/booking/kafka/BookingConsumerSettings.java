package com.middleberth.booking.kafka;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How many threads take booking requests off Kafka.
 *
 * Kafka gives each partition to exactly one thread, so the partition count is the
 * ceiling: with 15 partitions, a sixteenth thread would never be handed any work.
 * With several instances it is instances x concurrency that meets that ceiling.
 *
 * This used to be unset, so Spring's default of one applied — a single thread
 * owned all fifteen partitions and every booking in the system went through it,
 * one after another. A thread dump caught it in Net.poll every time: waiting on
 * Postgres, not computing. Measured, it peaked at 36% of one core while the
 * machine sat nearly idle and people waited for their answers.
 *
 * More threads do not make any single booking faster. They let many bookings wait
 * on the database at the same time instead of in a line.
 */
@ConfigurationProperties(prefix = "middleberth.booking-requests")
public record BookingConsumerSettings(int concurrency) {

    public BookingConsumerSettings {
        if (concurrency < 1) {
            throw new IllegalStateException("middleberth.booking-requests.concurrency is " + concurrency
                    + " — at least one thread has to read booking requests, or nothing is ever booked");
        }
    }
}
