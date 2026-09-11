package com.middleberth.payment.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.middleberth.payment.dto.RefundRequest;
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
 * Reading refund requests from booking-service — payment-service's first listener.
 *
 * Same protection as booking's payment listener, for the same reason: this is
 * money. Retry with growing gaps for a couple of minutes, then park the request on
 * refund-requests.DLT instead of dropping it. A dropped refund request is a
 * customer who paid, got nothing, and never gets their money back.
 */
@Configuration
public class RefundRequestsConfig {

    public static final String REFUND_REQUESTS = "refund-requests";
    public static final String REFUND_REQUESTS_DLT = REFUND_REQUESTS + ".DLT";

    /** Declared on both sides — whoever touches a topic first decides its partitions. */
    @Bean
    NewTopic refundRequests() {
        return new NewTopic(REFUND_REQUESTS, 15, (short) 1);
    }

    @Bean
    NewTopic refundRequestsDeadLetters() {
        return new NewTopic(REFUND_REQUESTS_DLT, 15, (short) 1);
    }

    @Bean
    ConcurrentKafkaListenerContainerFactory<String, RefundRequest> refundRequestsFactory(
            ConsumerFactory<?, ?> bootConsumerFactory, ObjectMapper objectMapper,
            KafkaTemplate<?, ?> kafkaTemplate, RefundRetrySettings retry) {

        Map<String, Object> props = new HashMap<>(bootConsumerFactory.getConfigurationProperties());
        props.remove(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG);
        props.remove(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG);
        // A consumer joining late must still see requests already sent — otherwise
        // a refund asked for while this service was restarting would be skipped.
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

        // booking-service stamps its own class name on the message; ignore it and
        // always build our copy. Wrapped so bad JSON is an error we can park.
        ErrorHandlingDeserializer<RefundRequest> value = new ErrorHandlingDeserializer<>(
                new JsonDeserializer<>(RefundRequest.class, objectMapper, false));
        DefaultKafkaConsumerFactory<String, RefundRequest> consumers =
                new DefaultKafkaConsumerFactory<>(props, new StringDeserializer(), value);
        consumers.setConfigureDeserializers(false);

        DeadLetterPublishingRecoverer toDeadLetters = new DeadLetterPublishingRecoverer(kafkaTemplate,
                (record, e) -> new TopicPartition(REFUND_REQUESTS_DLT, record.partition()));
        ExponentialBackOff backOff = new ExponentialBackOff(retry.initialInterval().toMillis(), 2.0);
        backOff.setMaxInterval(retry.maxInterval().toMillis());
        backOff.setMaxAttempts(retry.maxAttempts());

        ConcurrentKafkaListenerContainerFactory<String, RefundRequest> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumers);
        factory.setCommonErrorHandler(new DefaultErrorHandler(toDeadLetters, backOff));
        return factory;
    }
}
