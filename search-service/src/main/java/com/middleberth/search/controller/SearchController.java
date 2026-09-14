package com.middleberth.search.controller;

import com.middleberth.search.dto.AvailabilityResponse;
import com.middleberth.search.dto.TrainSummary;
import com.middleberth.search.service.AvailabilityService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(name = "Trains", description = "Read only, no login.")
public class SearchController {

    private final AvailabilityService availabilityService;

    @GetMapping
    @Operation(summary = "List the trains")
    public List<TrainSummary> trains() {
        return availabilityService.allTrains();
    }

    @GetMapping("/{trainNumber}/availability")
    @Operation(summary = "Free berths for a train, date and class",
            description = "date is yyyy-MM-dd and class is 3A or SL. A date that is not on sale "
                    + "answers UNKNOWN. Served from Redis, never from the booking database.")
    public AvailabilityResponse availability(
            @PathVariable String trainNumber,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam("class") String coachClass) {
        return availabilityService.availability(trainNumber, date, coachClass);
    }
}
