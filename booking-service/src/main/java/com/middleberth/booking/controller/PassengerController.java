package com.middleberth.booking.controller;

import com.middleberth.booking.dto.PassengerDetails;
import com.middleberth.booking.dto.PassengerResponse;
import com.middleberth.booking.service.PassengerService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * The saved passengers for whoever is calling.
 *
 * Read before the rush, not during it — the client loads this list, the user
 * picks somebody, and the booking request carries their name, email and phone
 * inline. So none of this is in the way at 10:00:00.
 *
 * Who is calling comes only from the gateway's header, exactly as for bookings.
 */
@RestController
@RequestMapping("/api/passengers")
@RequiredArgsConstructor
public class PassengerController {

    private final PassengerService passengers;

    @GetMapping
    public List<PassengerResponse> list(@RequestHeader(BookingController.USER_ID) Long userId) {
        return passengers.listSaved(userId).stream().map(PassengerResponse::of).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PassengerResponse add(@RequestHeader(BookingController.USER_ID) Long userId,
                                 @Valid @RequestBody PassengerDetails details) {
        return PassengerResponse.of(passengers.add(userId, details));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@RequestHeader(BookingController.USER_ID) Long userId,
                       @PathVariable Long id) {
        passengers.remove(userId, id);
    }
}
