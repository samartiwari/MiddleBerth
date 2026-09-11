package com.middleberth.search.exception;

public class TrainNotFoundException extends RuntimeException {

    public TrainNotFoundException(String trainNumber) {
        super("No train with number " + trainNumber);
    }
}
