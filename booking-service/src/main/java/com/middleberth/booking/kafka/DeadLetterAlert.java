package com.middleberth.booking.kafka;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Notices when a message has been given up on.
 *
 * A dead letter topic is only useful if somebody looks at it. Everything in this
 * project retries and then parks rather than dropping — which is the right
 * choice — but a parked message is a person whose ticket, refund or mail needs a
 * human, and until now nothing would ever have said so.
 *
 * This does two things, and deliberately nothing else:
 *
 *   logs at ERROR, with the topic, key, offset and the reason the message failed,
 *     which Kafka records in headers when it parks it
 *   counts it, as middleberth.dead.letters, tagged by topic
 *
 * The counter is the part that matters in a real deployment: Prometheus scrapes
 * /actuator/metrics and one alert rule on "more than zero" is enough. Reading the
 * message does not consume it — it stays on the topic, so it can still be
 * examined or replayed by hand.
 *
 * It does NOT try to parse the payload. A message parked because its JSON could
 * not be read would fail the same way here, and then the alert itself would need
 * an alert.
 */
@Component
@Slf4j
public class DeadLetterAlert {

    private final MeterRegistry meters;

    public DeadLetterAlert(MeterRegistry meters) {
        this.meters = meters;
    }

    @KafkaListener(topics = {BookingRequestsConfig.BOOKING_REQUESTS_DLT, PaymentEventsConfig.PAYMENT_EVENTS_DLT},
                   groupId = "booking-service-dead-letters",
                   containerFactory = "deadLetterFactory")
    public void parked(ConsumerRecord<String, String> record) {
        Counter.builder("middleberth.dead.letters")
                .description("messages given up on and parked for a human")
                .tag("topic", record.topic())
                .register(meters)
                .increment();

        log.error("DEAD LETTER on {} [partition {} offset {}] key={} reason={} — needs a human. Payload: {}",
                record.topic(), record.partition(), record.offset(), record.key(),
                header(record, KafkaHeaders.DLT_EXCEPTION_MESSAGE), record.value());
    }

    private String header(ConsumerRecord<String, String> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null ? "unknown" : new String(header.value(), StandardCharsets.UTF_8);
    }

    /**
     * Plain strings, whatever the payload was. The records on a dead letter topic
     * are not all valid JSON — that is one of the reasons they are there.
     */
    @Configuration
    static class Factory {

        @Bean
        ConcurrentKafkaListenerContainerFactory<String, String> deadLetterFactory(
                ConsumerFactory<?, ?> bootConsumerFactory) {

            Map<String, Object> props = new HashMap<>(bootConsumerFactory.getConfigurationProperties());
            props.remove(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG);
            props.remove(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG);
            // Anything parked while this service was down must still be reported.
            props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

            DefaultKafkaConsumerFactory<String, String> consumers = new DefaultKafkaConsumerFactory<>(
                    props, new StringDeserializer(), new StringDeserializer());
            consumers.setConfigureDeserializers(false);

            ConcurrentKafkaListenerContainerFactory<String, String> factory =
                    new ConcurrentKafkaListenerContainerFactory<>();
            factory.setConsumerFactory(consumers);
            return factory;
        }
    }
}
