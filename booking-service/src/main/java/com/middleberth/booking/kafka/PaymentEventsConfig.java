package com.middleberth.booking.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.middleberth.booking.dto.PaymentEvent;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.apache.kafka.common.TopicPartition;
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
 * Settings for reading payment-events — booking-service's second listener.
 *
 * The first listener (booking-requests) is configured in application.properties
 * to turn every message into a BookingCommand. That cannot apply here, so this
 * listener gets its own deserializer: ignore payment-service's type header (it
 * names a class that only exists in payment-service) and always build our
 * PaymentEvent.
 *
 * Built carefully so it does not replace Boot's defaults. Declaring a
 * ConsumerFactory BEAN would make Boot's own one back off, and then the
 * booking-requests listener would silently start using these settings too. So
 * the factory is made inline, and only the listener factory is a bean — under a
 * different name from Boot's.
 */
@Configuration
public class PaymentEventsConfig {

    public static final String PAYMENT_EVENTS = "payment-events";

    /** Where payment events go if they still fail after every retry — parked, not lost. */
    public static final String PAYMENT_EVENTS_DLT = PAYMENT_EVENTS + ".DLT";

    /**
     * payment-service owns this topic and declares it with 15 partitions. It is
     * declared here as well, identically, because whoever touches a topic first
     * decides how many partitions it gets: if booking-service subscribed before
     * payment-service had created it, the broker would auto-create it with ONE,
     * and payment-service's declaration would then find it already there and
     * leave it that way. Declaring it on both sides makes the start order harmless.
     */
    @Bean
    NewTopic paymentEvents() {
        return new NewTopic(PAYMENT_EVENTS, 15, (short) 1);
    }

    /** Same partition count as the source, so a failed message keeps its partition. */
    @Bean
    NewTopic paymentEventsDeadLetters() {
        return new NewTopic(PAYMENT_EVENTS_DLT, 15, (short) 1);
    }

    @Bean
    ConcurrentKafkaListenerContainerFactory<String, PaymentEvent> paymentEventsFactory(
            ConsumerFactory<?, ?> bootConsumerFactory, ObjectMapper objectMapper,
            KafkaTemplate<?, ?> kafkaTemplate, PaymentRetrySettings retry) {

        Map<String, Object> props = new HashMap<>(bootConsumerFactory.getConfigurationProperties());
        props.keySet().removeIf(k -> k.startsWith("spring.json."));   // those are for BookingCommand
        props.remove(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG);
        props.remove(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG);

        // Wrapped, so a message that is not valid JSON becomes an ordinary error that
        // can be sent to the dead letter topic — instead of failing inside the poll,
        // where the listener would trip over it again and again.
        ErrorHandlingDeserializer<PaymentEvent> value = new ErrorHandlingDeserializer<>(
                new JsonDeserializer<>(PaymentEvent.class, objectMapper, false));
        DefaultKafkaConsumerFactory<String, PaymentEvent> consumers =
                new DefaultKafkaConsumerFactory<>(props, new StringDeserializer(), value);
        consumers.setConfigureDeserializers(false);   // already configured above; don't re-apply the props

        ConcurrentKafkaListenerContainerFactory<String, PaymentEvent> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumers);
        factory.setCommonErrorHandler(paymentErrorHandler(kafkaTemplate, retry));
        return factory;
    }

    /**
     * What happens when confirming a payment throws.
     *
     * Spring Kafka's default retries 10 times and then DROPS the message. Measured
     * here: the attempts are about half a second apart (each retry re-polls Kafka),
     * so it covers roughly 4.5 seconds. That rides out a short blip — but a database
     * restart or failover takes 10 to 60 seconds, and then a customer who paid never
     * gets a ticket, with nothing left anywhere to try again. Razorpay cannot help by
     * then; payment-service has already told it "got it".
     *
     * So instead:
     *   - retry with growing gaps (1s, 2s, 4s ... up to 30s), for about two minutes
     *   - if it still fails, publish it to payment-events.DLT rather than dropping it,
     *     so it can be looked at and replayed once whatever broke is fixed
     *
     * The second part is the one that matters most: nothing is ever thrown away.
     */
    private DefaultErrorHandler paymentErrorHandler(KafkaTemplate<?, ?> kafkaTemplate, PaymentRetrySettings retry) {
        DeadLetterPublishingRecoverer toDeadLetters = new DeadLetterPublishingRecoverer(kafkaTemplate,
                (record, e) -> new TopicPartition(PAYMENT_EVENTS_DLT, record.partition()));

        ExponentialBackOff backOff = new ExponentialBackOff(retry.initialInterval().toMillis(), 2.0);
        backOff.setMaxInterval(retry.maxInterval().toMillis());
        backOff.setMaxAttempts(retry.maxAttempts());

        return new DefaultErrorHandler(toDeadLetters, backOff);
    }
}
