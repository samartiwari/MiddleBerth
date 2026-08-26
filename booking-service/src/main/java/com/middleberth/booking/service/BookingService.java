package com.middleberth.booking.service;

import com.middleberth.booking.domain.Booking;
import com.middleberth.booking.domain.BookingStatus;
import com.middleberth.booking.domain.Seat;
import com.middleberth.booking.dto.BookingCommand;
import com.middleberth.booking.dto.BookingResult;
import com.middleberth.booking.repository.BookingRepository;
import com.middleberth.booking.repository.SeatRepository;
import com.middleberth.booking.repository.TrainRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
@RequiredArgsConstructor
public class BookingService {

    private final TrainRepository trainRepo;
    private final SeatRepository seatRepo;
    private final BookingRepository bookingRepo;
    private final BookingTransaction bookingTransaction;

    public BookingResult book(BookingCommand cmd) {
        //Find train id from the request
        Long trainId = trainRepo.findByNumber(cmd.trainNumber())
                .orElseThrow(() -> new TrainNotFoundException(cmd.trainNumber()))
                .getId();

        // Optimisation only. Two threads can both look, both find nothing, and
        // both go on to insert — the UNIQUE constraint below is the guarantee.
        Optional<Booking> alreadyDone = bookingRepo.findByUserIdAndRequestId(cmd.userId(), cmd.requestId());
        if (alreadyDone.isPresent()) {
            return toResult(alreadyDone.get());
        }

        //try to book
        try {
            return bookingTransaction.claimOrWaitlist(cmd, trainId);
        } catch (DataIntegrityViolationException duplicate) {
            // Somebody else got there with the same request id. Their row is
            // committed by now, so return what they got rather than failing.
            return bookingRepo.findByUserIdAndRequestId(cmd.userId(), cmd.requestId())
                    .map(this::toResult)
                    .orElseThrow(() -> duplicate);
        }
    }

    //changes the seat number 42 -> 32-B like a user friendly manner
    private BookingResult toResult(Booking booking) {
        if (booking.getStatus() == BookingStatus.WAITLISTED) {
            return BookingResult.waitlisted(booking.getWaitlistPos());
        }
        String label = seatRepo.findById(booking.getSeatId())
                .map(Seat::label)
                .orElse(null);
        return BookingResult.held(label);
    }
}
