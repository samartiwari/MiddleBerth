package com.middleberth.booking.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;

/**
 * One physical berth, on one train, on one date.
 *
 * This is the row the whole project fights over. The claim query locks it with
 * FOR UPDATE SKIP LOCKED, so keep it small — no relationships, no lazy loading,
 * nothing that costs an extra query while a lock is held.
 */
@Entity
@Table(name = "seat")
@Getter
@Setter
@NoArgsConstructor
public class Seat {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "train_id", nullable = false)
    private Long trainId;

    @Column(name = "travel_date", nullable = false)
    private LocalDate travelDate;

    @Column(name = "coach_class", nullable = false, length = 4)
    private String coachClass;

    @Column(name = "coach", nullable = false, length = 4)
    private String coach;

    @Column(name = "seat_no", nullable = false, length = 6)
    private String seatNo;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 12)
    private SeatStatus status;

    public Seat(Long trainId, LocalDate travelDate, String coachClass,
                String coach, String seatNo, SeatStatus status) {
        this.trainId = trainId;
        this.travelDate = travelDate;
        this.coachClass = coachClass;
        this.coach = coach;
        this.seatNo = seatNo;
        this.status = status;
    }

    /** "B2-31" — what the user is shown. */
    public String label() {
        return coach + "-" + seatNo;
    }
}
