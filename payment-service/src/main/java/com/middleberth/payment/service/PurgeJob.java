package com.middleberth.payment.service;

import com.middleberth.payment.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneId;

/**
 * Old payments go, so this database does not grow for ever.
 *
 * One row per Pay Now, every day, none of which anybody reads again once the
 * journey is past. booking-service already deletes travel dates older than a
 * week; this keeps the same window, so the two stay in step and nothing can ask
 * about a payment whose booking is gone.
 *
 * A real system would archive rather than delete — somebody's accountant will
 * want last year's payments. This is a demonstration, and it says so.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PurgeJob {

    private final PaymentRepository paymentRepo;
    private final PurgeSettings purge;

    @Scheduled(cron = "${middleberth.purge.at}", zone = "${middleberth.purge.zone}")
    public void run() {
        purgeBefore(OffsetDateTime.now(ZoneId.of(purge.zone())).minusDays(purge.keepDays()));
    }

    /** Public so a test can pass a cutoff instead of waiting a week. */
    @Transactional
    public int purgeBefore(OffsetDateTime cutoff) {
        int removed = paymentRepo.deleteByCreatedAtBefore(cutoff);
        if (removed > 0) {
            log.info("Removed {} payments older than {}", removed, cutoff.toLocalDate());
        }
        return removed;
    }
}
