package com.middleberth.booking.service;

import com.middleberth.booking.domain.SeatStatus;
import com.middleberth.booking.dto.SeatCountEvent;
import com.middleberth.booking.kafka.SeatCountPublisher;
import com.middleberth.booking.repository.SeatRepository;
import com.middleberth.booking.repository.TrainRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * Tells search-service how many berths are free.
 *
 * One place for it, because it is now done from three: a new booking, a released
 * hold, and a payment that arrived late and was given a free berth. That last one
 * was missing, and search quietly claimed a berth was free when it had just been
 * taken.
 *
 * Always called AFTER the transaction has committed, so the number describes
 * something that really happened. A lost count is harmless — the next one
 * replaces it, and the snapshot job repairs anything missed.
 */
@Component
@RequiredArgsConstructor
public class SeatCountAnnouncer {

    private final SeatRepository seatRepo;
    private final TrainRepository trainRepo;
    private final SeatCountPublisher publisher;

    public void announce(Long trainId, LocalDate travelDate, String coachClass) {
        announce(trainNumberOf(trainId), trainId, travelDate, coachClass);
    }

    public void announce(String trainNumber, Long trainId, LocalDate travelDate, String coachClass) {
        int free = seatRepo.countByTrainIdAndTravelDateAndCoachClassAndStatus(
                trainId, travelDate, coachClass, SeatStatus.FREE);
        publisher.publish(new SeatCountEvent(trainNumber, travelDate, coachClass, free));
    }

    private String trainNumberOf(Long trainId) {
        return trainRepo.findById(trainId).orElseThrow().getNumber();
    }
}
