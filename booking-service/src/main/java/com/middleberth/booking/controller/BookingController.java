package com.middleberth.booking.controller;

import com.middleberth.booking.dto.BookingRequest;
import com.middleberth.booking.dto.BookingResponse;
import com.middleberth.booking.service.BookingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/bookings")
@RequiredArgsConstructor
public class BookingController {

    private final BookingService bookingService;

    /**
     * 200, not 201. The same requestId sent twice returns the same booking and
     * creates nothing, so "Created" would be a lie on every retry.
     */
    @PostMapping
    public BookingResponse book(@Valid @RequestBody BookingRequest request) {
        return BookingResponse.from(bookingService.book(request.toCommand()));
    }
}
