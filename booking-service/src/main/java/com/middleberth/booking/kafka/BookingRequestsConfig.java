package com.middleberth.booking.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.middleberth.booking.dto.BookingCommand;
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
 * A booking request must not be dropped either.
 *
 * Intake now waits for the broker before answering 202, so the message is
 * definitely on the queue. That guarantee was hollow while this listener used
 * Spring's default error handler: ten quick retries and then the message is
 * THROWN AWAY. A database restart lasts longer than ten retries, and the person
 * who was promised a request id would poll PENDING forever.
 *
 * So: retry with growing gaps for about two minutes, then park the command on
 * booking-requests.DLT, where something notices it (DeadLetterAlert).
 */
@Configuration
public class BookingRequestsConfig {

    public static final String BOOKING_REQUESTS_DLT = KafkaTopicConfig.BOOKING_REQUESTS + ".DLT";

    @Bean
    NewTopic bookingRequestDeadLetters() {
        return new NewTopic(BOOKING_REQUESTS_DLT, 15, (short) 1);
    }

    @Bean
    ConcurrentKafkaListenerContainerFactory<String, BookingCommand> bookingRequestsFactory(
            ConsumerFactory<?, ?> bootConsumerFactory, ObjectMapper objectMapper,
            KafkaTemplate<?, ?> kafkaTemplate, BookingRetrySettings retry,
            BookingConsumerSettings consumer) {

        Map<String, Object> props = new HashMap<>(bootConsumerFactory.getConfigurationProperties());
        props.remove(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG);
        props.remove(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG);

        ErrorHandlingDeserializer<BookingCommand> value = new ErrorHandlingDeserializer<>(
                new JsonDeserializer<>(BookingCommand.class, objectMapper, false));
        DefaultKafkaConsumerFactory<String, BookingCommand> consumers =
                new DefaultKafkaConsumerFactory<>(props, new StringDeserializer(), value);
        consumers.setConfigureDeserializers(false);

        DeadLetterPublishingRecoverer toDeadLetters = new DeadLetterPublishingRecoverer(kafkaTemplate,
                (record, e) -> new TopicPartition(BOOKING_REQUESTS_DLT, record.partition()));
        ExponentialBackOff backOff = new ExponentialBackOff(retry.initialInterval().toMillis(), 2.0);
        backOff.setMaxInterval(retry.maxInterval().toMillis());
        backOff.setMaxAttempts(retry.maxAttempts());

        ConcurrentKafkaListenerContainerFactory<String, BookingCommand> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumers);
        factory.setCommonErrorHandler(new DefaultErrorHandler(toDeadLetters, backOff));
        // At most one thread per partition this instance owns — see BookingConsumerSettings.
        // Order is still kept where it matters: one partition is only ever read by one
        // thread, so one train, date and class is still handled strictly in arrival order.
        factory.setConcurrency(consumer.concurrency());
        return factory;
    }
}
