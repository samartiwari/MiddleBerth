package com.middleberth.notification;

import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.kafka.KafkaConnectionDetails;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A dead letter topic nobody reads is a bin.
 *
 * This puts a message straight onto the dead letter topic — which is what a
 * message that has been given up on looks like — and checks that something
 * notices and counts it. The counter is what a monitoring system alerts on.
 *
 * The parking itself is already covered elsewhere; this is about the noticing.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class DeadLetterAlertTest {

    @Autowired MeterRegistry meters;
    @Autowired KafkaConnectionDetails kafka;

    @Test
    void a_parked_message_is_counted_so_it_can_be_alerted_on() {
        double before = count();

        publish("booking-events.DLT", "5512|A7X2", "{\"whatever\":\"a message somebody gave up on\"}");

        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < deadline && count() <= before) {
            sleep();
        }
        assertThat(count()).as("middleberth.dead.letters went up").isGreaterThan(before);
    }

    private double count() {
        return meters.find("middleberth.dead.letters").counters().stream()
                .mapToDouble(c -> c.count()).sum();
    }

    private void publish(String topic, String key, String body) {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        try (Producer<String, String> producer = new KafkaProducer<>(props)) {
            producer.send(new ProducerRecord<>(topic, key, body));
            producer.flush();
        }
    }

    private void sleep() {
        try {
            Thread.sleep(50);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
