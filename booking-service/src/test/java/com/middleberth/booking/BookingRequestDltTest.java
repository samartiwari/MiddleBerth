package com.middleberth.booking;

import com.middleberth.booking.dto.BookingCommand;
import com.middleberth.booking.service.BookingService;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.kafka.KafkaConnectionDetails;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

/**
 * Intake waiting for the broker is only half a guarantee.
 *
 * The other half is here: a request that is on the queue but cannot be processed
 * must not be quietly thrown away. Spring's default error handler does exactly
 * that — ten quick retries, then the message is gone — and a database restart
 * lasts a great deal longer than ten retries. The person who was promised a
 * request id would poll PENDING for ever, and nobody would know why.
 *
 * With its own error handler the request is retried and then parked on
 * booking-requests.DLT, where DeadLetterAlert reports it.
 *
 * The retry gaps are cut right down here so the test takes a second instead of
 * two minutes.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@TestPropertySource(properties = {
        "middleberth.booking-requests.retry.initial-interval=PT0.2S",
        "middleberth.booking-requests.retry.max-interval=PT0.5S",
        "middleberth.booking-requests.retry.max-attempts=3"
})
class BookingRequestDltTest {

    @Autowired KafkaConnectionDetails kafka;
    @Autowired JdbcTemplate jdbc;

    /** Stands in for anything that keeps failing: the database being down, say. */
    @MockitoSpyBean BookingService bookingService;

    @BeforeEach
    void wipe() {
        TestDatabase.wipe(jdbc);
    }

    @Test
    void a_request_that_cannot_be_processed_is_parked_and_not_dropped() {
        doThrow(new IllegalStateException("database is down")).when(bookingService).book(any(BookingCommand.class));

        try (Consumer<String, String> parked = deadLetters()) {
            publish("12951|2026-08-25|3A", """
                    {"requestId":"DOOMED","userId":5512,"trainNumber":"12951",\
                    "travelDate":"2026-08-25","coachClass":"3A",\
                    "passenger":{"name":"Test Passenger","email":"passenger@example.invalid","phone":"9876543210"}}""");

            ConsumerRecord<String, String> dead = awaitOne(parked);
            assertThat(dead.value()).as("the request itself, kept for a human").contains("DOOMED");
            assertThat(dead.headers().lastHeader("kafka_dlt-exception-message")).isNotNull();
        }
    }

    // ---------- helpers ----------

    private void publish(String key, String body) {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        try (Producer<String, String> producer = new KafkaProducer<>(props)) {
            producer.send(new ProducerRecord<>("booking-requests", key, body));
            producer.flush();
        }
    }

    private Consumer<String, String> deadLetters() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        Consumer<String, String> consumer = new KafkaConsumer<>(props);
        List<TopicPartition> partitions = consumer.partitionsFor("booking-requests.DLT").stream()
                .map(p -> new TopicPartition(p.topic(), p.partition())).toList();
        consumer.assign(partitions);
        consumer.seekToEnd(partitions);
        partitions.forEach(consumer::position);
        return consumer;
    }

    private ConsumerRecord<String, String> awaitOne(Consumer<String, String> consumer) {
        List<ConsumerRecord<String, String>> out = new ArrayList<>();
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (System.nanoTime() < deadline && out.isEmpty()) {
            consumer.poll(Duration.ofMillis(200)).forEach(out::add);
        }
        assertThat(out).as("the request was parked, not dropped").hasSize(1);
        return out.get(0);
    }
}
