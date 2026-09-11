package com.middleberth.booking.exception;

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

    /** Malformed JSON, or a date that isn't a date. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiError unreadable(HttpMessageNotReadableException e) {
        return new ApiError("MALFORMED_REQUEST", "Request body could not be read");
    }
}
