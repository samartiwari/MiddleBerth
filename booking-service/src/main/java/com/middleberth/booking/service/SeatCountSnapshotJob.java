package com.middleberth.booking.service;

import com.middleberth.booking.dto.SeatCountEvent;
import com.middleberth.booking.kafka.SeatCountPublisher;
import com.middleberth.booking.repository.SeatCountRow;
import com.middleberth.booking.repository.SeatRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Publishes every seat count, from scratch.
 *
 * Two problems, one job.
 *
 * COLD START. search-service answers from Redis and nothing else — deliberately,
 * so a browsing flood can never reach this database. But an empty Redis meant it
 * could only say UNKNOWN until somebody happened to book that train. Now the
 * first snapshot goes out seconds after this service starts, and a freshly
 * started search-service reads it and can answer immediately.
 *
 * DRIFT. Every other count is published in response to something happening. Any
 * one of those can be lost — they are sent after their transaction commits, on
 * purpose — and then search is quietly wrong until the next event for that train.
 * This replaces every count on a slow loop, so nothing stays wrong for long.
 *
 * Same shape as payment-service's reconciliation job: a fast path that is usually
 * right, and a slow one that fixes what the fast path missed.
 *
 * It runs on every pod, and that is harmless: they all publish the same numbers,
 * keyed by train, and the last one wins.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SeatCountSnapshotJob {

    private final SeatRepository seatRepo;
    private final SeatCountPublisher publisher;

    /**
     * fixedDelay, so the first snapshot goes out as soon as the service is up —
     * which is the cold start case — and then every few minutes after that.
     */
    @Scheduled(fixedDelayString = "${middleberth.seat-counts.snapshot-interval}")
    public void run() {
        int published = publishSnapshot();
        log.debug("Published a snapshot of {} seat counts", published);
    }

    /** Public so tests can ask for one instead of waiting for the clock. */
    public int publishSnapshot() {
        List<SeatCountRow> counts = seatRepo.freeCountsFromToday();
        for (SeatCountRow row : counts) {
            publisher.publish(new SeatCountEvent(
                    row.getTrainNumber(), row.getTravelDate(), row.getCoachClass(), row.getFreeSeats()));
        }
        return counts.size();
    }
}
