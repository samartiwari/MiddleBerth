package com.middleberth.notification.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.middleberth.notification.dto.NotificationEvent;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.util.backoff.ExponentialBackOff;

import java.util.HashMap;
import java.util.Map;

/**
 * Reading what booking-service has to say.
 *
 * Same shape as the other listeners in this project: ignore the sender's class
 * name, retry with growing gaps, and park anything that still will not work on a
 * dead letter topic instead of dropping it.
 */
@Configuration
public class BookingEventsConfig {

    public static final String BOOKING_EVENTS = "booking-events";
    public static final String BOOKING_EVENTS_DLT = BOOKING_EVENTS + ".DLT";

    /** Declared here as well as in booking-service — whoever touches it first decides its partitions. */
    @Bean
    NewTopic bookingEvents() {
        return new NewTopic(BOOKING_EVENTS, 15, (short) 1);
    }

    @Bean
    NewTopic bookingEventsDeadLetters() {
        return new NewTopic(BOOKING_EVENTS_DLT, 15, (short) 1);
    }

    @Bean
    ConcurrentKafkaListenerContainerFactory<String, NotificationEvent> bookingEventsFactory(
            ConsumerFactory<?, ?> bootConsumerFactory, ObjectMapper objectMapper,
            KafkaTemplate<?, ?> kafkaTemplate, BookingEventsRetrySettings retry) {

        Map<String, Object> props = new HashMap<>(bootConsumerFactory.getConfigurationProperties());
        props.remove(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG);
        props.remove(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG);
        // Restarting this service must not skip the tickets that were announced
        // while it was down.
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

        ErrorHandlingDeserializer<NotificationEvent> value = new ErrorHandlingDeserializer<>(
                new JsonDeserializer<>(NotificationEvent.class, objectMapper, false));
        DefaultKafkaConsumerFactory<String, NotificationEvent> consumers =
                new DefaultKafkaConsumerFactory<>(props, new StringDeserializer(), value);
        consumers.setConfigureDeserializers(false);

        DeadLetterPublishingRecoverer toDeadLetters = new DeadLetterPublishingRecoverer(kafkaTemplate,
                (record, e) -> new TopicPartition(BOOKING_EVENTS_DLT, record.partition()));
        ExponentialBackOff backOff = new ExponentialBackOff(retry.initialInterval().toMillis(), 2.0);
        backOff.setMaxInterval(retry.maxInterval().toMillis());
        backOff.setMaxAttempts(retry.maxAttempts());

        ConcurrentKafkaListenerContainerFactory<String, NotificationEvent> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumers);
        factory.setCommonErrorHandler(new DefaultErrorHandler(toDeadLetters, backOff));
        return factory;
    }
}
