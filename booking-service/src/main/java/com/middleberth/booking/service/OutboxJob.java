package com.middleberth.booking.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Empties the outbox, every second, on every pod. */
@Component
@RequiredArgsConstructor
@Slf4j
public class OutboxJob {

    private final OutboxPublisher publisher;
    private final OutboxSettings outbox;

    @Scheduled(fixedDelayString = "${middleberth.outbox.interval}")
    public void run() {
        int sent = 0;
        int batch;
        do {
            batch = publisher.publishBatch();     // commits here
            sent += batch;
        } while (batch == outbox.batchSize());    // a full batch means there may be more

        if (sent > 0) {
            log.info("Sent {} notes from the outbox", sent);
        }
    }
}
