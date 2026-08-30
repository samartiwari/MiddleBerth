package com.middleberth.booking.kafka;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class KafkaTopicConfig {

    //Topic name
    public static final String BOOKING_REQUESTS = "booking-requests";

    /**
     * 15 partitions. More than this project needs, and costs nothing.
     *
     * Partitions can be increased later but never decreased — and increasing
     * breaks ordering, because the key-to-partition mapping is hash % count.
     * The same train would suddenly map somewhere else while its old messages
     * sat in the old partition. So pick generously up front.
     *
     * One replica: a single broker in dev and on the VM.
     */
    @Bean
    NewTopic bookingRequests() {
        return new NewTopic(BOOKING_REQUESTS, 15, (short) 1);
    } //name,partition,replicas
}
