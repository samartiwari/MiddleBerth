package com.middleberth.booking;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Empties every table before a test.
 *
 * One TRUNCATE with CASCADE, rather than deleting table by table. Deleting
 * piecemeal has to go children-before-parents, and every table added later
 * (quota_counter in phase 5 broke all four test classes at once) makes each hand
 * written delete sequence wrong. CASCADE does not care about the order.
 *
 * The database lives for the whole test run, so without this each test would
 * start on whatever the previous one left behind.
 */
final class TestDatabase {

    private TestDatabase() {
    }

    static void wipe(JdbcTemplate jdbc) {
        jdbc.execute("TRUNCATE booking, seat, quota_counter, train, outbox, passenger CASCADE");
    }
}
