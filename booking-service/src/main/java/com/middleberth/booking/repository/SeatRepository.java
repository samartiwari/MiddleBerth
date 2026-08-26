package com.middleberth.booking.repository;

import com.middleberth.booking.domain.Seat;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
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
}
