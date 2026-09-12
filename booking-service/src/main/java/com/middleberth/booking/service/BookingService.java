package com.middleberth.booking.service;

import com.middleberth.booking.domain.Booking;
import com.middleberth.booking.domain.BookingStatus;
import com.middleberth.booking.domain.Seat;
import com.middleberth.booking.cache.OutcomeCache;
import com.middleberth.booking.dto.BookingCommand;
import com.middleberth.booking.exception.NotOnSaleException;
import com.middleberth.booking.exception.TrainNotFoundException;
import com.middleberth.booking.exception.WaitlistFullException;
import com.middleberth.booking.dto.BookingResult;
import com.middleberth.booking.repository.BookingRepository;
import com.middleberth.booking.repository.SeatRepository;
import com.middleberth.booking.repository.TrainRepository;
import com.middleberth.booking.repository.WaitlistCounter;
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
    private final WaitlistCounter waitlistCounter;
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
            rememberIfRegretted(cmd, result);
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
     * A regret leaves no row, so the only place the waiting page can learn about
     * it is Redis. Kept for much longer than an ordinary cached answer, because it
     * is not a snapshot of something that might change — it is the final word, and
     * it can never go stale.
     *
     * If Redis loses it anyway, the page stops hearing anything and eventually
     * gives up. That is survivable: asking again gets an immediate WAITLIST_FULL
     * from the door, which is the same answer by a different route.
     */
    private void rememberIfRegretted(BookingCommand cmd, BookingResult result) {
        if (result.status() == BookingStatus.REGRETTED) {
            outcomes.putRegret(cmd.userId(), cmd.requestId());
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
     * Three indexed reads at the door, and every one of them earns its place by
     * saving a pointless trip through the queue:
     *
     *   no such train      -> 404, rather than silence and PENDING for ever
     *   date not open yet  -> 409, rather than being queued and answered "full",
     *                         which is what it looked like before, because no
     *                         berths means no waitlist either
     *   nothing left       -> 409, rather than a full transaction to find out
     *
     * The last one is the one that carries weight under load. In the 2,000-person
     * run, two thirds of the traffic could never have been given anything, and all
     * of it used to queue, get consumed, take a transaction and write a row to say
     * so. Now it is turned away in a single read.
     *
     * All three are checked before the request is queued, which means this is the
     * only place in the system where the answer "no" is allowed to be a guess —
     * the counter can fill in the moment after it is read. That is fine: the
     * consumer checks again for real, atomically, and the worst case is a request
     * that gets in and is regretted a second later.
     */
    public void assertBookable(String trainNumber, LocalDate travelDate, String coachClass) {
        Long trainId = trainRepo.findByNumber(trainNumber)
                .orElseThrow(() -> new TrainNotFoundException(trainNumber)).getId();

        if (!seatRepo.existsByTrainIdAndTravelDateAndCoachClass(trainId, travelDate, coachClass)) {
            throw new NotOnSaleException(trainNumber, travelDate, coachClass);
        }

        if (waitlistCounter.isFull(trainId, travelDate, coachClass)) {
            throw new WaitlistFullException(trainNumber, travelDate, coachClass);
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
