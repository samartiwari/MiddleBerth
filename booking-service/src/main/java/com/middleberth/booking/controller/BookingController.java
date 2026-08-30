package com.middleberth.booking.controller;

import com.middleberth.booking.dto.BookingAccepted;
import com.middleberth.booking.dto.BookingRequest;
import com.middleberth.booking.dto.BookingStatusResponse;
import com.middleberth.booking.kafka.BookingPublisher;
import com.middleberth.booking.service.BookingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/bookings")
@RequiredArgsConstructor
public class BookingController {

    private final BookingPublisher publisher;
    private final BookingService bookingService;

    /**
     * Does almost nothing on purpose: validate, drop a message on the queue,
     * reply. A couple of milliseconds, so the socket closes immediately and
     * Tomcat never builds a queue of its own.
     *
     * The booking itself happens behind Kafka, at its own pace.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public BookingAccepted book(@Valid @RequestBody BookingRequest request) {
        bookingService.assertTrainExists(request.trainNumber());   // 404 now, not silence later
        publisher.publish(request.toCommand());
        return BookingAccepted.pending(request.requestId());
    }

    /**
     * What the page polls. PENDING until the consumer has got to it.
     *
     * userId is a query parameter for now; once the gateway is doing auth it
     * comes from the token instead, and cannot be spoofed.
     */
    @GetMapping("/{requestId}")
    public BookingStatusResponse status(@PathVariable String requestId,
                                        @RequestParam Long userId) {
        return bookingService.outcomeOf(userId, requestId)
                .map(BookingStatusResponse::of)
                .orElseGet(BookingStatusResponse::pending);
    }
}
