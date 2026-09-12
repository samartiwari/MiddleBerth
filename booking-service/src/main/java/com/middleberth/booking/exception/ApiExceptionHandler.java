package com.middleberth.booking.exception;

import com.middleberth.booking.payment.PaymentUnavailableException;
import com.middleberth.booking.kafka.QueueUnavailableException;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(TrainNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ApiError trainNotFound(TrainNotFoundException e) {
        return new ApiError("TRAIN_NOT_FOUND", e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiError invalidRequest(MethodArgumentNotValidException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + " " + f.getDefaultMessage())
                .sorted()
                .collect(Collectors.joining(", "));
        return new ApiError("INVALID_REQUEST", detail);
    }

    /**
     * No X-User-Id means the request did not come through the gateway, so there is
     * no checked identity. That is "we don't know who you are" — 401, not 400.
     */
    @ExceptionHandler(MissingRequestHeaderException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public ApiError missingIdentity(MissingRequestHeaderException e) {
        return new ApiError("UNAUTHENTICATED", "Missing " + e.getHeaderName() + " — requests must come through the gateway");
    }

    @ExceptionHandler(BookingNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ApiError bookingNotFound(BookingNotFoundException e) {
        return new ApiError("BOOKING_NOT_FOUND", e.getMessage());
    }

    /** Already paid, expired, regretted, or past its deadline. */
    @ExceptionHandler(NotPayableException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ApiError notPayable(NotPayableException e) {
        return new ApiError("NOT_PAYABLE", e.getMessage());
    }

    /** payment-service is down or too slow. The hold is untouched — try again. */
    @ExceptionHandler(PaymentUnavailableException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public ApiError paymentUnavailable(PaymentUnavailableException e) {
        return new ApiError("PAYMENT_UNAVAILABLE", "Could not start the payment — your hold is safe, try again");
    }

    /**
     * The queue would not take the request. Nothing has happened, so trying again
     * with the same request id is both safe and the right thing to do.
     */
    @ExceptionHandler(QueueUnavailableException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public ApiError queueUnavailable(QueueUnavailableException e) {
        return new ApiError("QUEUE_UNAVAILABLE",
                "Could not accept the request — nothing was booked, try again with the same requestId");
    }

    /** Malformed JSON, or a date that isn't a date. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiError unreadable(HttpMessageNotReadableException e) {
        return new ApiError("MALFORMED_REQUEST", "Request body could not be read");
    }
}
