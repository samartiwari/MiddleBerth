package com.middleberth.booking.kafka;

/**
 * The request could not be put on the queue, so it is not going to happen.
 *
 * Better to say so than to hand back a request id for work nobody will ever do.
 * The client retries with the SAME request id, and the UNIQUE constraint makes
 * that safe however many times it takes.
 */
public class QueueUnavailableException extends RuntimeException {

    public QueueUnavailableException(String key, Throwable cause) {
        super("Could not queue the booking request for " + key, cause);
    }
}
