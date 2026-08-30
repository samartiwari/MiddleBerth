package com.middleberth.booking.kafka;

import com.middleberth.booking.dto.BookingCommand;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class BookingPublisher {

    private final KafkaTemplate<String, BookingCommand> kafka;

    /**
     * The key is what makes the whole thing work: hash(key) % partitions decides
     * the partition, so every request for one train, date and class lands in the
     * same partition — and therefore is handled by one consumer, one at a time,
     * in arrival order.
     *
     * That gives fairness (first in the queue wins) and keeps contention for one
     * train out of every other train's way.
     */
    public void publish(BookingCommand command) {
        kafka.send(KafkaTopicConfig.BOOKING_REQUESTS, keyFor(command), command);
    }

    static String keyFor(BookingCommand c) {
        return c.trainNumber() + "|" + c.travelDate() + "|" + c.coachClass();
    }
}
