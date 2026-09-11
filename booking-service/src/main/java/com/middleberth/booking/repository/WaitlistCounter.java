package com.middleberth.booking.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Optional;

/**
 * Hands out waitlist numbers, and enforces the cap.
 *
 * Plain SQL rather than an entity: the whole job is one atomic statement on one
 * row, and mapping a table just to increment a number would add nothing.
 * JdbcTemplate joins the same transaction as the JPA work around it.
 */
@Component
@RequiredArgsConstructor
public class WaitlistCounter {

    private final JdbcTemplate jdbc;

    /**
     * The next waitlist number, or empty if the waitlist is full (REGRET).
     *
     * Read and write are ONE statement, so there is no gap for anyone to slip
     * into. Postgres locks the row, adds one, returns the new value, releases.
     * That is what replaced count() + 1 — the race is gone by construction, not
     * because Kafka happens to serialise one train.
     */
    public Optional<Integer> take(long trainId, LocalDate travelDate, String coachClass) {
        ensureRow(trainId, travelDate, coachClass);
        return jdbc.queryForList("""
                UPDATE quota_counter
                   SET wl_issued = wl_issued + 1,
                       wl_live   = wl_live + 1
                 WHERE train_id = ? AND travel_date = ? AND coach_class = ?
                   AND wl_live < wl_cap
                RETURNING wl_issued
                """, Integer.class, trainId, travelDate, coachClass)
                .stream().findFirst();
    }

    /**
     * A slot freed up — someone's waitlist hold expired, or they were promoted to
     * a berth. Only wl_live goes down; wl_issued never does, so no number is ever
     * handed out twice.
     *
     * Must run in the same transaction as the status change that caused it, or a
     * crash between the two would leave the counter permanently wrong.
     */
    public void release(long trainId, LocalDate travelDate, String coachClass) {
        jdbc.update("""
                UPDATE quota_counter
                   SET wl_live = wl_live - 1
                 WHERE train_id = ? AND travel_date = ? AND coach_class = ?
                   AND wl_live > 0
                """, trainId, travelDate, coachClass);
    }

    /**
     * Creates the counter the first time anyone is waitlisted for this train,
     * date and class. The cap is the number of berths in that class — a waitlist
     * much longer than the berths is a promise that cannot be kept, and money
     * that would only be refunded.
     *
     * ON CONFLICT DO NOTHING, so every request after the first is a no-op.
     */
    private void ensureRow(long trainId, LocalDate travelDate, String coachClass) {
        jdbc.update("""
                INSERT INTO quota_counter (train_id, travel_date, coach_class, wl_cap)
                SELECT ?, ?, ?, COUNT(*)
                  FROM seat
                 WHERE train_id = ? AND travel_date = ? AND coach_class = ?
                ON CONFLICT (train_id, travel_date, coach_class) DO NOTHING
                """, trainId, travelDate, coachClass, trainId, travelDate, coachClass);
    }
}
