package com.middleberth.booking.service;

import com.middleberth.booking.repository.QuotaWindow;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.Set;

/**
 * Every day, the window rolls forward one day.
 *
 *     yesterday        today          tomorrow
 *     deleted   <--    on sale   <--  opens at noon
 *
 * Two halves of one idea, which is why they are one job:
 *
 *   OPEN    tomorrow's berths go on sale, built from what each train has
 *   CLOSE   travel dates that have already gone are deleted
 *
 * Without the second half the database grows for ever — a few hundred berths and
 * a few thousand bookings a day, none of which anybody will ever read again. With
 * it, storage settles at a few days' worth and stays flat whether this runs for a
 * week or a year.
 *
 * It runs on every pod, and that is safe: opening uses ON CONFLICT DO NOTHING so
 * the second pod creates nothing, and deleting a row that another pod already
 * deleted is not an error.
 *
 * What it will NEVER do is reset a date that is on sale. Re-running leaves every
 * existing berth exactly as it is, held or confirmed or free — a "reset" that
 * wiped live bookings would be the worst bug this system could have.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class QuotaWindowJob {

    private final QuotaWindow window;
    private final SeatCountAnnouncer announcer;
    private final QuotaSettings quota;

    /**
     * Noon in India, every day. IRCTC opens tatkal at 10:00; this is the same
     * idea with a time of our own, and it is a property so it can be moved
     * without a rebuild.
     */
    @Scheduled(cron = "${middleberth.quota.open-at}", zone = "${middleberth.quota.zone}")
    public void run() {
        roll(today());
    }

    /**
     * Also at startup, so a system brought up fresh has something to sell instead
     * of waiting until noon. Idempotent, so doing it twice costs nothing.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void openOnStartup() {
        if (quota.openOnStartup()) {
            roll(today());
        }
    }

    /** Public so tests can hand it a chosen day instead of waiting for one. */
    public int roll(LocalDate today) {
        int opened = open(today.plusDays(quota.daysAhead()));
        int removed = window.purgeTravelDatesBefore(today.minusDays(quota.keepDays()));
        removed += window.purgeSentOutboxBefore(today.minusDays(quota.keepDays()));

        if (opened > 0 || removed > 0) {
            log.info("Quota window rolled: {} berths opened for {}, {} old rows removed",
                    opened, today.plusDays(quota.daysAhead()), removed);
        }
        return opened;
    }

    private int open(LocalDate travelDate) {
        int opened = 0;
        Set<String> announced = new HashSet<>();

        for (QuotaWindow.Quota quotaRow : window.quotas()) {
            int berths = window.openBerths(quotaRow, travelDate);
            opened += berths;

            // Tell search-service, but once per train and class rather than once
            // per coach — the count is the same number either way.
            if (berths > 0 && announced.add(quotaRow.trainId() + "|" + quotaRow.coachClass())) {
                announcer.announce(quotaRow.trainId(), travelDate, quotaRow.coachClass());
            }
        }
        return opened;
    }

    private LocalDate today() {
        return LocalDate.now(ZoneId.of(quota.zone()));
    }
}
