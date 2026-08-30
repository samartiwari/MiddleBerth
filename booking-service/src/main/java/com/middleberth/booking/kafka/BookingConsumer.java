package com.middleberth.booking.kafka;

import com.middleberth.booking.dto.BookingCommand;
import com.middleberth.booking.exception.TrainNotFoundException;
import com.middleberth.booking.service.BookingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Reads booking requests off the queue and does the actual work.
 *
 * One partition belongs to exactly one consumer thread, and that thread handles
 * its records one at a time. So for any given train, bookings are processed
 * sequentially — which is where the fairness comes from, and why the waitlist
 * counter has nothing to race against.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class BookingConsumer {

    private final BookingService bookingService;

    @KafkaListener(topics = KafkaTopicConfig.BOOKING_REQUESTS, groupId = "booking-service")
    public void handle(BookingCommand command) {
        try {
            bookingService.book(command);
        } catch (TrainNotFoundException e) {
            // Retrying will never help — the train does not exist. Drop it and
            // move on rather than blocking the partition behind a poison message.
            log.warn("Dropping request {}: {}", command.requestId(), e.getMessage());
        }
    }
}
