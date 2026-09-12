package com.middleberth.booking.repository;

import java.time.LocalDate;

/** One line of the snapshot: this train, this date, this class, this many free. */
public interface SeatCountRow {

    String getTrainNumber();

    LocalDate getTravelDate();

    String getCoachClass();

    int getFreeSeats();
}
