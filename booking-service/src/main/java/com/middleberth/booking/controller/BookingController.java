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

/**
 * Who is calling comes ONLY from the X-User-Id header, which the gateway sets
 * from the checked token after throwing away any the client sent.
 *
 * That trust is safe because this service has no published port — the gateway
 * is the only way in. Reach it directly and there is no X-User-Id, so the request
 * is refused.
 */
@RestController
@RequestMapping("/api/bookings")
@RequiredArgsConstructor
public class BookingController {

    public static final String USER_ID = "X-User-Id";

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
    public BookingAccepted book(@RequestHeader(USER_ID) Long userId,
                                @Valid @RequestBody BookingRequest request) {
        bookingService.assertTrainExists(request.trainNumber());   // 404 now, not silence later
        publisher.publish(request.toCommand(userId));
        return BookingAccepted.pending(request.requestId());
    }

    /**
     * What the page polls. PENDING until the consumer has got to it.
     *
     * Used to take userId as a query parameter, which let anyone read anyone's
     * booking by changing the number. Now it is the caller's own id, from the
     * gateway — so you can only ever see your own.
     */
    @GetMapping("/{requestId}")
    public BookingStatusResponse status(@RequestHeader(USER_ID) Long userId,
                                        @PathVariable String requestId) {
        return bookingService.outcomeOf(userId, requestId)
                .map(BookingStatusResponse::of)
                .orElseGet(BookingStatusResponse::pending);
    }
}
