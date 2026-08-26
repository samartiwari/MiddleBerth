package com.middleberth.booking.dto;

import java.time.LocalDate;

public record BookingCommand(String requestId,
                             Long userId,
                             String trainNumber,
                             LocalDate travelDate,
                             String coachClass) {
}
