package com.middleberth.booking.repository;

import com.middleberth.booking.domain.Booking;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface BookingRepository extends JpaRepository<Booking, Long> {

    /**
     * Used when the UNIQUE constraint on request_id rejects an insert — we look up
     * the booking that won and return that instead of failing. Scoped to the user, so
     * two people using the same request id do not see each other's bookings.
     */
    Optional<Booking> findByUserIdAndRequestId(Long userId, String requestId);

    /**
     * The same booking, locked — so a payment and the expiry job can never both
     * act on it. Whichever takes the lock first wins, and the other sees the
     * result.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from Booking b where b.userId = :userId and b.requestId = :requestId")
    Optional<Booking> lockByUserIdAndRequestId(@Param("userId") Long userId,
                                               @Param("requestId") String requestId);

    /**
     * Unpaid holds past the cutoff, for the expiry job.
     *
     * SKIP LOCKED — the same trick that claims seats. Every pod runs the expiry
     * job, and each one takes a different batch instead of two pods releasing the
     * same hold. No extra library, no leader election.
     *
     * It also means a hold that is being paid for right now (locked by the payment)
     * is simply skipped rather than expired underneath it.
     *
     * A hold where Pay Now was clicked has to be past graceCutoff too — it gets a
     * few extra minutes, because the money may be on its way.
     */
    @Query(value = """
            SELECT * FROM booking
            WHERE status IN ('HELD', 'WAITLIST_HELD')
              AND pay_by < :cutoff
              AND (payment_started_at IS NULL OR pay_by < :graceCutoff)
            ORDER BY pay_by
            LIMIT :batchSize
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<Booking> lockExpiredHolds(@Param("cutoff") Instant cutoff,
                                   @Param("graceCutoff") Instant graceCutoff,
                                   @Param("batchSize") int batchSize);

    /**
     * The next PAID waitlister for a berth that just freed up — lowest ticket
     * number first.
     *
     * Only WAITLISTED, never WAITLIST_HELD: someone who has not paid is not really
     * in the queue yet. They keep their number and are first for the next berth
     * once they do.
     *
     * Served entirely by idx_waitlist from V1, which is exactly this shape.
     */
    @Query(value = """
            SELECT * FROM booking
            WHERE train_id = :trainId
              AND travel_date = :travelDate
              AND coach_class = :coachClass
              AND status = 'WAITLISTED'
            ORDER BY waitlist_pos
            LIMIT 1
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    Optional<Booking> lockNextWaitlisted(@Param("trainId") Long trainId,
                                         @Param("travelDate") LocalDate travelDate,
                                         @Param("coachClass") String coachClass);
}
