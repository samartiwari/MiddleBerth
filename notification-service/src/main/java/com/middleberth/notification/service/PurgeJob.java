package com.middleberth.notification.service;

import com.middleberth.notification.repository.SentMailRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneId;

/**
 * Old records of sent mail go, so this database does not grow for ever.
 *
 * Deleting them is only safe because of WHY the table exists: it stops the same
 * mail being sent twice, and a duplicate can only arrive while the message is
 * still on Kafka. Kafka holds messages for seven days, so seven days of memory
 * is enough. Deleting sooner than that could genuinely send somebody a second
 * copy of their ticket.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PurgeJob {

    private final SentMailRepository sentMailRepo;
    private final PurgeSettings purge;

    @Scheduled(cron = "${middleberth.purge.at}", zone = "${middleberth.purge.zone}")
    public void run() {
        purgeBefore(OffsetDateTime.now(ZoneId.of(purge.zone())).minusDays(purge.keepDays()));
    }

    /** Public so a test can pass a cutoff instead of waiting a week. */
    @Transactional
    public int purgeBefore(OffsetDateTime cutoff) {
        int removed = sentMailRepo.deleteBySentAtBefore(cutoff);
        if (removed > 0) {
            log.info("Removed {} sent mail records older than {}", removed, cutoff.toLocalDate());
        }
        return removed;
    }
}
