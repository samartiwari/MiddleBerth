package com.middleberth.booking.dto;

/** The only two things worth troubling a passenger with. */
public enum NotificationType {
    /** They have a berth. */
    TICKET_CONFIRMED,
    /** The booking did not survive, and the money is going back. */
    BOOKING_CANCELLED
}
