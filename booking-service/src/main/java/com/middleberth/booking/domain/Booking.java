package com.middleberth.booking.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * One person's attempt to book. Either they got a berth (HELD) or they did not
 * (WAITLISTED).
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
    @Column(name = "status", nullable = false, length = 12)
    private BookingStatus status;

    /** Null unless waitlisted. */
    @Column(name = "waitlist_pos")
    private Integer waitlistPos;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    public static Booking held(String requestId, Long userId, Long trainId,
                               LocalDate travelDate, String coachClass, Long seatId) {
        Booking b = new Booking();
        b.requestId = requestId;
        b.userId = userId;
        b.trainId = trainId;
        b.travelDate = travelDate;
        b.coachClass = coachClass;
        b.seatId = seatId;
        b.status = BookingStatus.HELD;
        return b;
    }

    public static Booking waitlisted(String requestId, Long userId, Long trainId,
                                     LocalDate travelDate, String coachClass, int position) {
        Booking b = new Booking();
        b.requestId = requestId;
        b.userId = userId;
        b.trainId = trainId;
        b.travelDate = travelDate;
        b.coachClass = coachClass;
        b.status = BookingStatus.WAITLISTED;
        b.waitlistPos = position;
        return b;
    }
}
