package com.middleberth.booking.service;

public class TrainNotFoundException extends RuntimeException {

    public TrainNotFoundException(String trainNumber) {
        super("No train with number " + trainNumber);
    }
}
