package com.middleberth.booking.controller;

import com.middleberth.booking.dto.BookingAccepted;
import com.middleberth.booking.dto.BookingRequest;
import com.middleberth.booking.dto.BookingStatusResponse;
import com.middleberth.booking.dto.PayNowResponse;
import com.middleberth.booking.dto.CancelledResponse;
import com.middleberth.booking.dto.RefundRequest;
import com.middleberth.booking.kafka.BookingPublisher;
import com.middleberth.booking.kafka.RefundRequestPublisher;
import com.middleberth.booking.service.BookingCancellation;
import com.middleberth.booking.service.CancelOutcome;
import com.middleberth.booking.service.BookingService;
import com.middleberth.booking.service.PayNowService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.concurrent.CompletableFuture;

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
    private final PayNowService payNowService;
    private final BookingCancellation cancellation;
    private final RefundRequestPublisher refunds;

    /**
     * Does almost nothing on purpose: validate, drop a message on the queue,
     * reply. A couple of milliseconds, so the socket closes immediately and
     * Tomcat never builds a queue of its own.
     *
     * The booking itself happens behind Kafka, at its own pace.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public CompletableFuture<BookingAccepted> book(@RequestHeader(USER_ID) Long userId,
                                                   @Valid @RequestBody BookingRequest request) {
        // 404 for a train that does not exist, 409 for a date that is not open and
        // 409 for a train with nothing left — all three at the door, so none of
        // them wastes a trip through the queue. The last one is most of the
        // traffic in a tatkal rush.
        bookingService.assertBookable(request.trainNumber(), request.travelDate(), request.coachClass());

        // Noted before it is queued, so the polls that follow are answered from
        // Redis rather than from the database the booking threads are using, until
        // a booking thread writes the answer over the note.
        bookingService.markPending(userId, request.requestId());

        // 202 only once the broker has really taken it — but the thread is not
        // held while that happens. Returning the future lets Spring release the
        // thread and write the reply when the acknowledgement arrives.
        return publisher.publish(request.toCommand(userId))
                .thenApply(queued -> BookingAccepted.pending(request.requestId()));
    }

    /**
     * What the page polls. PENDING until the consumer has got to it, with how long
     * to leave it before asking again.
     *
     * Used to take userId as a query parameter, which let anyone read anyone's
     * booking by changing the number. Now it is the caller's own id, from the
     * gateway — so you can only ever see your own.
     */
    @GetMapping("/{requestId}")
    public BookingStatusResponse status(@RequestHeader(USER_ID) Long userId,
                                        @PathVariable String requestId) {
        var poll = bookingService.poll(userId, requestId);
        return poll.result() != null
                ? BookingStatusResponse.of(poll.result())
                : BookingStatusResponse.pending(poll.retryAfter());
    }

    /**
     * Give the ticket up.
     *
     * The berth does not go back on sale — it goes to the next paid waitlister,
     * inside one transaction, exactly as when a hold expires. Money that was paid
     * is refunded in full; this project has no cancellation fee.
     */
    @PostMapping("/{requestId}/cancel")
    public CancelledResponse cancel(@RequestHeader(USER_ID) Long userId,
                                    @PathVariable String requestId) {
        CancelOutcome outcome = cancellation.cancel(userId, requestId);
        if (outcome.refundDue()) {
            refunds.publishAndWait(RefundRequest.forCancellation(userId, requestId));
        }
        return new CancelledResponse(outcome.pnr(), outcome.refundDue());
    }

    /**
     * Pay Now. Returns what the browser needs to open Razorpay's checkout. The
     * payment itself happens on Razorpay's page; we hear about it by webhook.
     */
    @PostMapping("/{requestId}/pay")
    public PayNowResponse pay(@RequestHeader(USER_ID) Long userId,
                              @PathVariable String requestId) {
        return payNowService.payNow(userId, requestId);
    }
}
