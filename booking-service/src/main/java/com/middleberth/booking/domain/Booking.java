package com.middleberth.booking.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.concurrent.ThreadLocalRandom;

/**
 * One person's attempt to book. Starts as a hold on a berth or a waitlist slot,
 * with a deadline to pay — see BookingStatus for the lifecycle.
 *
 * train/date/class are stored here rather than reached through seatId, because
 * a waitlisted booking has no seat and would otherwise not know which train it
 * is waiting for.
 */
@Entity
@Table(name = "booking")
@Getter
@Setter
@NoArgsConstructor
public class Booking {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "request_id", nullable = false, length = 40)   // UNIQUE (user_id, request_id) — see V1__init.sql
    private String requestId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "train_id", nullable = false)
    private Long trainId;

    @Column(name = "travel_date", nullable = false)
    private LocalDate travelDate;

    @Column(name = "coach_class", nullable = false, length = 4)
    private String coachClass;

    /** Null when waitlisted. */
    @Column(name = "seat_id")
    private Long seatId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private BookingStatus status;

    /** Null unless waitlisted. */
    @Column(name = "waitlist_pos")
    private Integer waitlistPos;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    /** The deadline the user is told. Null once paid, expired, or regretted. */
    @Column(name = "pay_by")
    private Instant payBy;

    @Column(name = "paid_at")
    private Instant paidAt;

    /** When Pay Now created an order. A hold with a payment under way gets extra time. */
    @Column(name = "payment_started_at")
    private Instant paymentStartedAt;

    /** Who the ticket is for, as it was when it was booked. */
    @Embedded
    private PassengerSnapshot passenger;

    /**
     * Issued when the ticket is paid for — confirmed or waitlisted, exactly as on
     * IRCTC, where a waitlisted ticket has a PNR too. Null until then, because a
     * hold nobody paid for is not a ticket.
     */
    @Column(name = "pnr", length = 10)
    private String pnr;

    public static Booking held(String requestId, Long userId, Long trainId,
                               LocalDate travelDate, String coachClass, PassengerSnapshot passenger,
                               Long seatId, Instant payBy) {
        Booking b = base(requestId, userId, trainId, travelDate, coachClass, passenger);
        b.seatId = seatId;
        b.status = BookingStatus.HELD;
        b.payBy = payBy;
        return b;
    }

    public static Booking waitlistHeld(String requestId, Long userId, Long trainId,
                                       LocalDate travelDate, String coachClass,
                                       PassengerSnapshot passenger, int position, Instant payBy) {
        Booking b = base(requestId, userId, trainId, travelDate, coachClass, passenger);
        b.status = BookingStatus.WAITLIST_HELD;
        b.waitlistPos = position;
        b.payBy = payBy;
        return b;
    }

    // There is no factory for a regret. It used to write a row here so the polling
    // page had somewhere to read the answer from, which meant storing a person's
    // name, email and phone number to record that they got nothing. The answer
    // lives in Redis now, and almost nobody reaches this point at all — the door
    // turns them away before the queue.

    // ---------- the lifecycle ----------

    /** Money arrived. A held berth is confirmed; a held waitlist slot is kept. */
    /**
     * Ten digits, random rather than counted up.
     *
     * A sequence would be simpler, but then PNRs are guessable: 1000000042 tells
     * you 1000000041 exists. Random out of nine billion does not. If two ever
     * collide the UNIQUE index refuses the second, the message is retried, and it
     * gets a different number.
     */
    private void issuePnr() {
        if (pnr == null) {
            pnr = String.valueOf(ThreadLocalRandom.current().nextLong(1_000_000_000L, 10_000_000_000L));
        }
    }

    public void markPaid(Instant at) {
        if (status == BookingStatus.HELD) {
            status = BookingStatus.CONFIRMED;
        } else if (status == BookingStatus.WAITLIST_HELD) {
            status = BookingStatus.WAITLISTED;
        } else {
            throw new IllegalStateException("Cannot pay for a booking that is " + status);
        }
        paidAt = at;
        payBy = null;
        issuePnr();      // paid for, so it is a ticket now — waitlisted counts
    }

    /**
     * Deadline passed with no payment.
     *
     * payBy is kept, not cleared: if money turns up later, it is judged against
     * this deadline. Paid before it means we were slow, and we try to honour it.
     * Paid after it means it really was late, and it is refunded.
     */
    public void expire() {
        if (!status.isHold()) {
            throw new IllegalStateException("Only a hold can expire, this one is " + status);
        }
        status = BookingStatus.EXPIRED;
    }

    public void markPaymentStarted(Instant at) {
        if (paymentStartedAt == null) {
            paymentStartedAt = at;
        }
    }

    /** A payment made in time arrived after the hold was released, and a berth was still free. */
    public void reinstateWithBerth(Long berthId, Instant paidAt) {
        requireExpired();
        this.seatId = berthId;
        this.waitlistPos = null;
        this.status = BookingStatus.CONFIRMED;
        this.paidAt = paidAt;
        issuePnr();
    }

    /** A payment made in time arrived after the waitlist hold was released, and there was room. */
    public void reinstateOnWaitlist(int position, Instant paidAt) {
        requireExpired();
        this.waitlistPos = position;
        this.status = BookingStatus.WAITLISTED;
        this.paidAt = paidAt;
        issuePnr();
    }

    private void requireExpired() {
        if (status != BookingStatus.EXPIRED) {
            throw new IllegalStateException("Only an expired booking can be reinstated, this one is " + status);
        }
    }

    /**
     * A berth freed up and this paid waitlister is next in line. They already paid
     * the full fare for a waitlisted ticket, so confirming costs them nothing more.
     */
    /**
     * Given up by the passenger. The berth is handed on by whoever calls this —
     * the booking only records that it is no longer theirs.
     */
    public void cancel() {
        if (status == BookingStatus.CANCELLED) {
            throw new IllegalStateException("Already cancelled");
        }
        status = BookingStatus.CANCELLED;
        seatId = null;
        waitlistPos = null;
        payBy = null;
    }

    public void promoteTo(Long berthId) {
        if (status != BookingStatus.WAITLISTED) {
            throw new IllegalStateException("Only a paid waitlister can be promoted, this one is " + status);
        }
        seatId = berthId;
        status = BookingStatus.CONFIRMED;
    }

    private static Booking base(String requestId, Long userId, Long trainId,
                                LocalDate travelDate, String coachClass,
                                PassengerSnapshot passenger) {
        Booking b = new Booking();
        b.requestId = requestId;
        b.userId = userId;
        b.trainId = trainId;
        b.travelDate = travelDate;
        b.coachClass = coachClass;
        b.passenger = passenger;
        return b;
    }
}
