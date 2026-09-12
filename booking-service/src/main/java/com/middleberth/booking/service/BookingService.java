package com.middleberth.booking.service;

import com.middleberth.booking.domain.Booking;
import com.middleberth.booking.domain.BookingStatus;
import com.middleberth.booking.domain.Seat;
import com.middleberth.booking.cache.OutcomeCache;
import com.middleberth.booking.dto.BookingCommand;
import com.middleberth.booking.exception.NotOnSaleException;
import com.middleberth.booking.exception.TrainNotFoundException;
import com.middleberth.booking.dto.BookingResult;
import com.middleberth.booking.repository.BookingRepository;
import com.middleberth.booking.repository.SeatRepository;
import com.middleberth.booking.repository.TrainRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class BookingService {

    private final TrainRepository trainRepo;
    private final SeatRepository seatRepo;
    private final BookingRepository bookingRepo;
    private final BookingTransaction bookingTransaction;
    private final SeatCountAnnouncer announcer;
    private final OutcomeCache outcomes;

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
            BookingResult result = bookingTransaction.claimOrWaitlist(cmd, trainId);
            publishSeatCount(cmd, trainId, result);
            return result;
        } catch (DataIntegrityViolationException duplicate) {
            // Somebody else got there with the same request id. Their row is
            // committed by now, so return what they got rather than failing.
            return bookingRepo.findByUserIdAndRequestId(cmd.userId(), cmd.requestId())
                    .map(this::toResult)
                    .orElseThrow(() -> duplicate);
        }
    }

    /**
     * Told to search-service so the availability page never has to ask this
     * database. Runs after the transaction has committed, so the number reflects
     * what actually landed.
     *
     * Only a claimed berth changes the count — waitlisting touches no seat rows.
     */
    private void publishSeatCount(BookingCommand cmd, Long trainId, BookingResult result) {
        if (result.status() != BookingStatus.HELD) {
            return;
        }
        announcer.announce(cmd.trainNumber(), trainId, cmd.travelDate(), cmd.coachClass());
    }

    /**
     * Checked at the door, before the request is queued. Rejecting a nonsense
     * train here costs one indexed lookup; accepting it means the consumer
     * silently drops it later and the user polls PENDING forever.
     */
    public void assertTrainExists(String trainNumber) {
        if (trainRepo.findByNumber(trainNumber).isEmpty()) {
            throw new TrainNotFoundException(trainNumber);
        }
    }

    /**
     * Two indexed lookups at the door, and both earn their place: a nonsense train
     * is a 404 now rather than silence later, and a date that has not opened is
     * told so rather than being queued, processed, and answered "sorry, full" —
     * which is what it looked like before, because no berths means no waitlist
     * either.
     */
    public void assertOnSale(String trainNumber, LocalDate travelDate, String coachClass) {
        Long trainId = trainRepo.findByNumber(trainNumber)
                .orElseThrow(() -> new TrainNotFoundException(trainNumber)).getId();

        if (!seatRepo.existsByTrainIdAndTravelDateAndCoachClass(trainId, travelDate, coachClass)) {
            throw new NotOnSaleException(trainNumber, travelDate, coachClass);
        }
    }

    /**
     * What the page polls for. Empty until the consumer has processed it.
     *
     * Redis first. A poll that finds nothing there reads Postgres and puts the
     * answer back, so the next one — a second later, and the one after that —
     * costs nothing. Postgres is still the truth; this only stops everybody
     * asking it the same question over and over.
     */
    public Optional<BookingResult> outcomeOf(Long userId, String requestId) {
        Optional<BookingResult> cached = outcomes.get(userId, requestId);
        if (cached.isPresent()) {
            return cached;
        }

        Optional<BookingResult> found = bookingRepo.findByUserIdAndRequestId(userId, requestId)
                .map(this::toResult);
        found.ifPresent(result -> outcomes.put(userId, requestId, result));
        return found;
    }

    //changes the seat number 42 -> 32-B like a user friendly manner
    private BookingResult toResult(Booking booking) {
        return switch (booking.getStatus()) {
            case HELD          -> BookingResult.held(seatLabel(booking), booking.getPayBy());
            case CONFIRMED     -> BookingResult.confirmed(seatLabel(booking), booking.getPnr());
            case WAITLIST_HELD -> BookingResult.waitlistHeld(booking.getWaitlistPos(), booking.getPayBy());
            case WAITLISTED    -> BookingResult.waitlisted(booking.getWaitlistPos(), booking.getPnr());
            case EXPIRED       -> BookingResult.expired();
            case REGRETTED     -> BookingResult.regretted();
            case CANCELLED     -> BookingResult.cancelled(booking.getPnr());
        };
    }

    private String seatLabel(Booking booking) {
        return seatRepo.findById(booking.getSeatId()).map(Seat::label).orElse(null);
    }
}
