package com.middleberth.booking.repository;

import com.middleberth.booking.domain.Passenger;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PassengerRepository extends JpaRepository<Passenger, Long> {

    List<Passenger> findByUserIdOrderByNameAsc(Long userId);

    /**
     * Scoped by user, always. A delete by id alone would let anyone remove
     * anyone's passenger by guessing a number — the same mistake that was fixed
     * in the booking status endpoint.
     */
    int deleteByIdAndUserId(Long id, Long userId);
}
