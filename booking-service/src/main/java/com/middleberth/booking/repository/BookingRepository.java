package com.middleberth.booking.repository;

import com.middleberth.booking.domain.Booking;
import com.middleberth.booking.domain.BookingStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Optional;

public interface BookingRepository extends JpaRepository<Booking, Long> {

    /**
     * Used when the UNIQUE constraint on request_id rejects an insert — we look up
     * the booking that won and return that instead of failing.
     */
    Optional<Booking> findByRequestId(String requestId);

    /** How many people are already waitlisted for this train, date and class. */
    int countByTrainIdAndTravelDateAndCoachClassAndStatus(Long trainId,
                                                          LocalDate travelDate,
                                                          String coachClass,
                                                          BookingStatus status);
}
