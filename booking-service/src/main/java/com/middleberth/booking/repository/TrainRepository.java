package com.middleberth.booking.repository;

import com.middleberth.booking.domain.Train;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface TrainRepository extends JpaRepository<Train, Long> {

    Optional<Train> findByNumber(String number);
}
