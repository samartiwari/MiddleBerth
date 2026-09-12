package com.middleberth.booking.service;

import com.middleberth.booking.domain.Booking;
import com.middleberth.booking.domain.BookingStatus;
import com.middleberth.booking.domain.Seat;
import com.middleberth.booking.domain.SeatStatus;
import com.middleberth.booking.repository.BookingRepository;
import com.middleberth.booking.repository.SeatRepository;
import com.middleberth.booking.repository.WaitlistCounter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Releases holds that were not paid in time.
 *
 * A separate class from the scheduled job on purpose — @Transactional only works
 * when the call crosses a Spring proxy. Same lesson as BookingService and
 * BookingTransaction.
 */
@Service
@RequiredArgsConstructor
public class HoldReleaser {

    /** A train, date and class whose count of FREE berths just went up. */
    public record FreedBerths(Long trainId, LocalDate travelDate, String coachClass) {
    }

    public record Batch(int released, Set<FreedBerths> freed) {
    }

    private final BookingRepository bookingRepo;
    private final SeatRepository seatRepo;
    private final WaitlistCounter waitlistCounter;
    private final HoldSettings holds;
    private final Outbox outbox;

    /**
     * One batch, one transaction.
     *
     * A released BERTH never passes through FREE if anyone is waiting — it goes
     * straight from the person who did not pay to the next paid waitlister, inside
     * this transaction. Otherwise a brand-new booking could grab it in between and
     * jump the whole queue. From initial.md: released seats flow down the waitlist
     * rather than going back to a free-for-all.
     */
    @Transactional
    public Batch releaseBatch(Instant now) {
        Instant cutoff = now.minus(holds.cushion());
        Instant graceCutoff = cutoff.minus(holds.paymentGrace());
        List<Booking> expired = bookingRepo.lockExpiredHolds(cutoff, graceCutoff, holds.batchSize());

        Set<FreedBerths> freed = new HashSet<>();

        for (Booking booking : expired) {
            BookingStatus was = booking.getStatus();
            booking.expire();

            if (was == BookingStatus.WAITLIST_HELD) {
                // gave up a waitlist slot — nothing else to hand on
                waitlistCounter.release(booking.getTrainId(), booking.getTravelDate(), booking.getCoachClass());
                continue;
            }

            // gave up a berth — next paid waitlister gets it, or it goes back on sale
            Seat berth = seatRepo.findById(booking.getSeatId()).orElseThrow();
            Optional<Booking> next = bookingRepo.lockNextWaitlisted(
                    booking.getTrainId(), booking.getTravelDate(), booking.getCoachClass());

            if (next.isPresent()) {
                next.get().promoteTo(berth.getId());
                berth.setStatus(SeatStatus.CONFIRMED);   // they already paid for the waitlisted ticket
                outbox.ticketConfirmed(next.get(), berth.label());   // the mail nobody was expecting
                waitlistCounter.release(booking.getTrainId(), booking.getTravelDate(), booking.getCoachClass());
            } else {
                berth.setStatus(SeatStatus.FREE);
                freed.add(new FreedBerths(booking.getTrainId(), booking.getTravelDate(), booking.getCoachClass()));
            }
        }
        return new Batch(expired.size(), freed);
    }
}
