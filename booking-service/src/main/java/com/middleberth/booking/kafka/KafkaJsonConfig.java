package com.middleberth.booking.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.kafka.DefaultKafkaProducerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.support.serializer.JsonSerializer;

/**
 * Makes Kafka write JSON the same way the HTTP API does.
 *
 * Spring Kafka's JsonSerializer builds its own ObjectMapper, which writes a
 * LocalDate as an array — [2026, 8, 25] — while Spring Boot's own ObjectMapper
 * (the one the REST endpoints use) writes "2026-08-25". Two JSON dialects in one
 * app, and search-service expects the string one.
 *
 * Caught by the shared contract test on its first run. This hands Kafka Boot's
 * ObjectMapper, so every topic and every endpoint speaks the same JSON.
 *
 * A customizer rather than a whole new ProducerFactory, on purpose — replacing
 * the factory would lose Boot's auto-configuration, including the bootstrap
 * servers that @ServiceConnection supplies in tests.
 */
@Configuration
public class KafkaJsonConfig {

    @Bean
    DefaultKafkaProducerFactoryCustomizer bootJsonForKafka(ObjectMapper objectMapper) {
        return factory -> factory.setValueSerializer(new JsonSerializer<>(objectMapper));
    }
}
