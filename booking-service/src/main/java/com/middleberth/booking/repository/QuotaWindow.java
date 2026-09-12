package com.middleberth.booking.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Opening a travel date, and closing the ones that have gone by.
 *
 * Plain SQL, like the waitlist counter: these are bulk statements over many rows,
 * and loading ten thousand berths into memory to save them back one at a time
 * would be slower and no clearer.
 */
@Component
@RequiredArgsConstructor
public class QuotaWindow {

    /** One coach of one class on one train: "12951 has 24 berths in 3A, coach B2". */
    public record Quota(Long trainId, String coachClass, String coach, int berths) {
    }

    private final JdbcTemplate jdbc;

    public List<Quota> quotas() {
        return jdbc.query("SELECT train_id, coach_class, coach, berths FROM train_quota ORDER BY train_id",
                (rs, n) -> new Quota(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getInt(4)));
    }

    /**
     * Puts a coach on sale for a date, and returns how many berths that created.
     *
     * ON CONFLICT DO NOTHING is what makes this safe to run twice, and safe to run
     * on every pod at once: the UNIQUE constraint on the berth decides, not a check
     * beforehand. It is also what stops a re-run resetting a berth somebody is
     * already holding — an existing row is left exactly as it is.
     */
    public int openBerths(Quota quota, LocalDate travelDate) {
        return jdbc.update("""
                INSERT INTO seat (train_id, travel_date, coach_class, coach, seat_no, status)
                SELECT ?, ?, ?, ?, n::text, 'FREE'
                  FROM generate_series(1, ?) AS n
                ON CONFLICT DO NOTHING
                """, quota.trainId(), travelDate, quota.coachClass(), quota.coach(), quota.berths());
    }

    /**
     * Deletes everything belonging to travel dates that have already gone.
     *
     * Without this the database grows for ever: a few hundred berths and a few
     * thousand bookings every single day, none of which anybody will look at again.
     * With it, storage settles at a few days' worth and stays there.
     *
     * Bookings first, because a booking points at a seat. Then the berths, then the
     * waitlist counters for those dates.
     *
     * In a system with real passengers you would archive these rather than delete
     * them — people expect to see last month's journeys. This one is a
     * demonstration, and it says so.
     */
    public int purgeTravelDatesBefore(LocalDate cutoff) {
        int bookings = jdbc.update("DELETE FROM booking WHERE travel_date < ?", cutoff);
        int berths = jdbc.update("DELETE FROM seat WHERE travel_date < ?", cutoff);
        jdbc.update("DELETE FROM quota_counter WHERE travel_date < ?", cutoff);
        return bookings + berths;
    }

    /**
     * Notes that have been sent and are older than the window. Anything still
     * unsent is left alone, however old — that is somebody's mail that never went.
     */
    public int purgeSentOutboxBefore(LocalDate cutoff) {
        // An OffsetDateTime, not an Instant: the driver cannot work out which SQL
        // type a bare Instant means, and says so at runtime rather than compile
        // time. Caught by a test before it could crash a real startup.
        return jdbc.update("DELETE FROM outbox WHERE sent_at IS NOT NULL AND sent_at < ?",
                cutoff.atStartOfDay().atOffset(ZoneOffset.UTC));
    }
}
