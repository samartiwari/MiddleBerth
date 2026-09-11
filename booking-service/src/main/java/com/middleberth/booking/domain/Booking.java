package com.middleberth.booking.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;

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

    public static Booking held(String requestId, Long userId, Long trainId,
                               LocalDate travelDate, String coachClass, Long seatId, Instant payBy) {
        Booking b = base(requestId, userId, trainId, travelDate, coachClass);
        b.seatId = seatId;
        b.status = BookingStatus.HELD;
        b.payBy = payBy;
        return b;
    }

    public static Booking waitlistHeld(String requestId, Long userId, Long trainId,
                                       LocalDate travelDate, String coachClass, int position,
                                       Instant payBy) {
        Booking b = base(requestId, userId, trainId, travelDate, coachClass);
        b.status = BookingStatus.WAITLIST_HELD;
        b.waitlistPos = position;
        b.payBy = payBy;
        return b;
    }

    /**
     * The waitlist was full. Written anyway so the page polling for this request
     * can be told REGRET — the API is asynchronous, so the answer has to be
     * stored somewhere. No seat, no position, no deadline.
     */
    public static Booking regretted(String requestId, Long userId, Long trainId,
                                    LocalDate travelDate, String coachClass) {
        Booking b = base(requestId, userId, trainId, travelDate, coachClass);
        b.status = BookingStatus.REGRETTED;
        return b;
    }

    // ---------- the lifecycle ----------

    /** Money arrived. A held berth is confirmed; a held waitlist slot is kept. */
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
    }

    /** Deadline passed with no payment. */
    public void expire() {
        if (!status.isHold()) {
            throw new IllegalStateException("Only a hold can expire, this one is " + status);
        }
        status = BookingStatus.EXPIRED;
        payBy = null;
    }

    /**
     * A berth freed up and this paid waitlister is next in line. They already paid
     * the full fare for a waitlisted ticket, so confirming costs them nothing more.
     */
    public void promoteTo(Long berthId) {
        if (status != BookingStatus.WAITLISTED) {
            throw new IllegalStateException("Only a paid waitlister can be promoted, this one is " + status);
        }
        seatId = berthId;
        status = BookingStatus.CONFIRMED;
    }

    private static Booking base(String requestId, Long userId, Long trainId,
                                LocalDate travelDate, String coachClass) {
        Booking b = new Booking();
        b.requestId = requestId;
        b.userId = userId;
        b.trainId = trainId;
        b.travelDate = travelDate;
        b.coachClass = coachClass;
        return b;
    }
}
