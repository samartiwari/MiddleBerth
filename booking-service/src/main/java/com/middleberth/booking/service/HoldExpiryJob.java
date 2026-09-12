package com.middleberth.booking.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Every few seconds, releases holds nobody paid for.
 *
 * Runs on every pod. That is safe because each batch is claimed with
 * FOR UPDATE SKIP LOCKED — two pods take two different batches, never the same
 * hold twice.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class HoldExpiryJob {

    private final HoldReleaser releaser;
    private final HoldSettings holds;
    private final SeatCountAnnouncer announcer;

    @Scheduled(fixedDelayString = "${middleberth.hold.expiry-interval}")
    public void run() {
        releaseExpired(Instant.now());
    }

    /** Public so tests can call it with a chosen "now" instead of waiting minutes. */
    public int releaseExpired(Instant now) {
        int total = 0;
        HoldReleaser.Batch batch;
        do {
            batch = releaser.releaseBatch(now);   // commits here
            total += batch.released();
            batch.freed().forEach(this::announceNewCount);
        } while (batch.released() == holds.batchSize());   // a full batch means there may be more

        if (total > 0) {
            log.info("Released {} expired holds", total);
        }
        return total;
    }

    /**
     * After the commit, so search-service is only ever told about something that
     * actually happened. Only berths that went back to FREE change the count — a
     * berth handed to a waitlister was taken before and is still taken.
     */
    private void announceNewCount(HoldReleaser.FreedBerths f) {
        announcer.announce(f.trainId(), f.travelDate(), f.coachClass());
    }
}
