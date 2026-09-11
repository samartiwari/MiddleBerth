package com.middleberth.search.service;

import com.middleberth.search.cache.SeatCountCache;
import com.middleberth.search.domain.Train;
import com.middleberth.search.dto.AvailabilityResponse;
import com.middleberth.search.dto.TrainSummary;
import com.middleberth.search.exception.TrainNotFoundException;
import com.middleberth.search.repository.TrainRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.OptionalInt;

@Service
@RequiredArgsConstructor
public class AvailabilityService {

    private final TrainRepository trainRepo;
    private final SeatCountCache cache;

    @Transactional(readOnly = true)
    public List<TrainSummary> allTrains() {
        return trainRepo.findAll().stream()
                .map(t -> new TrainSummary(t.getNumber(), t.getName()))
                .toList();
    }

    /**
     * Postgres for the train's name, Redis for the number of berths. Never
     * booking-service's database — that is the entire point of this service.
     *
     * The number is a hint, not a promise. It can be a few seconds old, and
     * someone seeing 23 and then being waitlisted is the system working, not a
     * bug. Keeping it exact would cost far more than it is worth when lakhs of
     * people are refreshing the page.
     */
    @Transactional(readOnly = true)
    public AvailabilityResponse availability(String trainNumber, LocalDate travelDate, String coachClass) {
        Train train = trainRepo.findByNumber(trainNumber)
                .orElseThrow(() -> new TrainNotFoundException(trainNumber));

        OptionalInt free = cache.get(trainNumber, travelDate, coachClass);

        if (free.isEmpty()) {
            // Redis has never been told about this train/date/class — cold cache,
            // or nothing has been booked yet. Say so rather than guess.
            return response(train, travelDate, coachClass, "UNKNOWN", null);
        }
        if (free.getAsInt() > 0) {
            return response(train, travelDate, coachClass, "AVAILABLE", free.getAsInt());
        }
        // Berths gone. How deep the waitlist is lives in booking-service, and
        // search-service deliberately does not ask it.
        return response(train, travelDate, coachClass, "WAITLIST", null);
    }

    private AvailabilityResponse response(Train train, LocalDate travelDate,
                                          String coachClass, String status, Integer freeSeats) {
        return new AvailabilityResponse(train.getNumber(), train.getName(),
                travelDate, coachClass, status, freeSeats);
    }
}
