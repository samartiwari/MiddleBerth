package com.middleberth.payment.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.autoconfigure.kafka.DefaultKafkaProducerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.support.serializer.JsonSerializer;

@Configuration
public class KafkaConfig {

    public static final String PAYMENT_EVENTS = "payment-events";

    @Bean
    NewTopic paymentEvents() {
        return new NewTopic(PAYMENT_EVENTS, 15, (short) 1);
    }

    /**
     * Kafka writes JSON with Boot's ObjectMapper, so dates come out as
     * "2026-08-25T10:02:30Z" and not as a raw number. Learned the hard way in
     * booking-service, where Spring Kafka's own mapper wrote dates as arrays and
     * only the shared contract test caught it. Done from the start here.
     */
    @Bean
    DefaultKafkaProducerFactoryCustomizer bootJsonForKafka(ObjectMapper objectMapper) {
        return factory -> factory.setValueSerializer(new JsonSerializer<>(objectMapper));
    }
}
