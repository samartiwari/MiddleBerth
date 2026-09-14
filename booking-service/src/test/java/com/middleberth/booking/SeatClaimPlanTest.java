package com.middleberth.booking;

import com.middleberth.booking.repository.SeatRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.Query;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Claiming a berth goes straight to that train's free berths, however many other
 * trains there are.
 *
 * Under a steady 1,000 bookings a second the same code sometimes kept up and
 * sometimes collapsed to under 200. The claim query asks for the free berth with
 * the lowest id, and the index it was meant to use had no id in it. So Postgres
 * often found it cheaper to walk the whole seat table in id order and throw away
 * every row that was not this train. Berths are laid out train by train, so a
 * claim on one of the last trains walked past 166,752 rows to lock one. Which plan
 * it chose depended on the statistics of the moment — hence the coin toss.
 *
 * Checked the way Postgres would really run it: as a one-off plan, and as the
 * reusable plan a pooled connection switches to after a few executions.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class SeatClaimPlanTest {

    private static final LocalDate DATE = LocalDate.of(2026, 8, 25);

    @Autowired JdbcTemplate jdbc;

    private long lastTrainId;

    /** The steady-rate load test's layout: 300 trains, 8 coaches of 72, stored train by train. */
    @BeforeEach
    void seed() {
        TestDatabase.wipe(jdbc);
        jdbc.execute("""
                INSERT INTO train (number, name)
                SELECT 'P' || lpad(n::text, 4, '0'), 'Plan Train ' || n FROM generate_series(1, 300) AS n
                """);
        jdbc.execute("""
                INSERT INTO seat (train_id, travel_date, coach_class, coach, seat_no, status)
                SELECT t.id, DATE '2026-08-25', c.cls, c.coach, s::text, 'FREE'
                  FROM train t
                 CROSS JOIN (VALUES ('3A', 'B1'), ('3A', 'B2'), ('3A', 'B3'), ('3A', 'B4'),
                                    ('SL', 'S1'), ('SL', 'S2'), ('SL', 'S3'), ('SL', 'S4')) AS c (cls, coach)
                 CROSS JOIN generate_series(1, 72) AS s
                 ORDER BY t.id, c.cls, c.coach, s
                """);
        // What autovacuum would have done a minute after the berths went on sale.
        jdbc.execute("ANALYZE train");
        jdbc.execute("ANALYZE seat");
        lastTrainId = jdbc.queryForObject("SELECT max(id) FROM train", Long.class);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"force_custom_plan", "force_generic_plan"})
    void claiming_a_berth_on_the_last_train_goes_straight_to_it(String planMode) throws Exception {
        List<String> plan = explainClaim(planMode, lastTrainId, "SL");

        assertThat(String.join("\n", plan))
                .as("no other train's rows read and thrown away on the way to this one")
                .doesNotContain("Rows Removed by Filter")
                .doesNotContain("Seq Scan");
    }

    /** The query exactly as the repository runs it, read off its @Query rather than copied here. */
    private List<String> explainClaim(String planMode, long trainId, String coachClass) throws Exception {
        String sql = SeatRepository.class
                .getMethod("claimFreeSeat", Long.class, LocalDate.class, String.class)
                .getAnnotation(Query.class).value()
                .replace(":trainId", "$1").replace(":travelDate", "$2").replace(":coachClass", "$3");

        return jdbc.execute((ConnectionCallback<List<String>>) connection -> {
            try (Statement st = connection.createStatement()) {
                st.execute("SET plan_cache_mode = " + planMode);
                st.execute("PREPARE claim_plan(bigint, date, varchar) AS " + sql);
                try {
                    List<String> lines = new ArrayList<>();
                    try (ResultSet rs = st.executeQuery("EXPLAIN (ANALYZE, COSTS OFF, TIMING OFF, SUMMARY OFF) "
                            + "EXECUTE claim_plan(" + trainId + ", DATE '" + DATE + "', '" + coachClass + "')")) {
                        while (rs.next()) {
                            lines.add(rs.getString(1));
                        }
                    }
                    System.out.println("\n" + planMode + ":\n" + String.join("\n", lines));
                    return lines;
                } finally {
                    st.execute("DEALLOCATE claim_plan");
                    st.execute("RESET plan_cache_mode");
                }
            }
        });
    }
}
