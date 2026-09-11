package com.middleberth.search.controller;

import com.middleberth.search.dto.AvailabilityResponse;
import com.middleberth.search.dto.TrainSummary;
import com.middleberth.search.service.AvailabilityService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

/**
 * Read only. This service never books anything.
 *
 * Before 10 AM everybody is refreshing these two endpoints, and none of that
 * load reaches booking-service or its database.
 */
@RestController
@RequestMapping("/api/trains")
@RequiredArgsConstructor
public class SearchController {

    private final AvailabilityService availabilityService;

    @GetMapping
    public List<TrainSummary> trains() {
        return availabilityService.allTrains();
    }

    @GetMapping("/{trainNumber}/availability")
    public AvailabilityResponse availability(
            @PathVariable String trainNumber,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam("class") String coachClass) {
        return availabilityService.availability(trainNumber, date, coachClass);
    }
}
