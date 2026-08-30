package com.middleberth.booking.dto;

/**
 * The 202 reply. Not an outcome — just "we have your request, here is the id to
 * ask about it with".
 */
public record BookingAccepted(String requestId, String status) {

    public static BookingAccepted pending(String requestId) {
        return new BookingAccepted(requestId, "PENDING");
    }
}
