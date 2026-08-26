package com.middleberth.booking.service;

import com.middleberth.booking.domain.Booking;
import com.middleberth.booking.domain.BookingStatus;
import com.middleberth.booking.domain.Seat;
import com.middleberth.booking.domain.SeatStatus;
import com.middleberth.booking.dto.BookingCommand;
import com.middleberth.booking.dto.BookingResult;
import com.middleberth.booking.repository.BookingRepository;
import com.middleberth.booking.repository.SeatRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * The atomic bit: take a berth and write the booking, or write a waitlist row.
 *
 * Separate class on purpose. BookingService has to catch the unique-constraint
 * violation and then read the row that won — and that read has to happen in a
 * NEW transaction, because a constraint violation aborts the one it happened in.
 * Calling a @Transactional method from another method in the same class does not
 * go through the Spring proxy, so the annotation would be silently ignored.
 */
@Component
@RequiredArgsConstructor
class BookingTransaction {

    private final SeatRepository seatRepo;
    private final BookingRepository bookingRepo;

    @Transactional
    BookingResult claimOrWaitlist(BookingCommand cmd, Long trainId) {
        //try to claim a seat
        Optional<Seat> claimed =
                seatRepo.claimFreeSeat(trainId, cmd.travelDate(), cmd.coachClass());

        if (claimed.isPresent()) {
            Seat seat = claimed.get();
            seat.setStatus(SeatStatus.HELD);       // locked until this commits
            bookingRepo.saveAndFlush(Booking.held(
                    cmd.requestId(), cmd.userId(), trainId,
                    cmd.travelDate(), cmd.coachClass(), seat.getId()));
            return BookingResult.held(seat.label());
        }

        int position = bookingRepo.countByTrainIdAndTravelDateAndCoachClassAndStatus(
                trainId, cmd.travelDate(), cmd.coachClass(), BookingStatus.WAITLISTED) + 1;

        bookingRepo.saveAndFlush(Booking.waitlisted(
                cmd.requestId(), cmd.userId(), trainId,
                cmd.travelDate(), cmd.coachClass(), position));
        return BookingResult.waitlisted(position);
    }
}
