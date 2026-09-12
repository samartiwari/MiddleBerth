package com.middleberth.booking.repository;

import com.middleberth.booking.domain.Seat;
import com.middleberth.booking.domain.SeatStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface SeatRepository extends JpaRepository<Seat, Long> {

    /**
     * Take one free berth on this train, date and class — the heart of the project.
     *
     * FOR UPDATE locks the row we picked, so nobody else can change it until this
     * transaction commits.
     *
     * SKIP LOCKED means: if a row is already locked by someone else, do not wait
     * for it — step over it and take the next one. That is what lets 500 threads
     * arriving in the same millisecond walk away with 500 different seats instead
     * of queueing behind the same row.
     *
     * Without SKIP LOCKED every thread would pick seat #1, 499 of them would block,
     * and the whole thing would run one booking at a time.
     *
     * Must be called inside a transaction. The lock is only held until commit, so
     * without one the row is unlocked the instant this method returns.
     */
    @Query(value = """
            SELECT * FROM seat
            WHERE train_id = :trainId
              AND travel_date = :travelDate
              AND coach_class = :coachClass
              AND status = 'FREE'
            ORDER BY id
            LIMIT 1
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    Optional<Seat> claimFreeSeat(@Param("trainId") Long trainId,
                                 @Param("travelDate") LocalDate travelDate,
                                 @Param("coachClass") String coachClass);

    /**
     * How many berths are still free. Published to search-service after every
     * claim so the availability page never has to ask this database itself.
     *
     * Hits idx_seat_lookup (train_id, travel_date, coach_class, status) exactly,
     * so it counts index entries and never touches the table.
     */
    /**
     * Every count in one query, for the snapshot that search-service starts from.
     *
     * Grouped in the database rather than by asking per train: one statement
     * instead of one per train and class. Only dates that can still be travelled,
     * because nobody is browsing last week.
     */
    @Query(value = """
            SELECT t.number       AS trainNumber,
                   s.travel_date  AS travelDate,
                   s.coach_class  AS coachClass,
                   count(*) FILTER (WHERE s.status = 'FREE') AS freeSeats
              FROM seat s
              JOIN train t ON t.id = s.train_id
             WHERE s.travel_date >= CURRENT_DATE
             GROUP BY t.number, s.travel_date, s.coach_class
            """, nativeQuery = true)
    List<SeatCountRow> freeCountsFromToday();

    int countByTrainIdAndTravelDateAndCoachClassAndStatus(Long trainId,
                                                          LocalDate travelDate,
                                                          String coachClass,
                                                          SeatStatus status);
}
