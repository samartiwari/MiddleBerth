package com.middleberth.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.middleberth.payment.domain.PaymentStatus;
import com.middleberth.payment.gateway.WebhookSignature;
import com.middleberth.payment.kafka.KafkaConfig;
import com.middleberth.payment.repository.PaymentRepository;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.kafka.KafkaConnectionDetails;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.*;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 5b: payment-service on its own, against the stub gateway.
 *
 * The tests play Razorpay for webhooks — they build the payload, sign it with the
 * webhook secret exactly the way Razorpay does, and POST it. The signature check
 * does not know or care who signed it, so this tests the real verification path.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PaymentApiTest {

    @Autowired TestRestTemplate http;
    @Autowired PaymentRepository paymentRepo;
    @Autowired WebhookSignature signature;
    @Autowired KafkaConnectionDetails kafka;

    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void wipe() {
        paymentRepo.deleteAllInBatch();
    }

    // ---------- orders ----------

    @Test
    void creating_an_order_records_it_as_created() throws Exception {
        JsonNode order = createOrder(5512, "A7X2", 240000);

        assertThat(order.get("orderId").asText()).startsWith("order_");
        assertThat(order.get("amountPaise").asLong()).isEqualTo(240000);
        assertThat(order.get("currency").asText()).isEqualTo("INR");
        assertThat(order.get("keyId").asText()).as("the PUBLIC key, for the browser").isEqualTo("rzp_test_stub");

        assertThat(paymentRepo.findByUserIdAndRequestId(5512L, "A7X2").orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.CREATED);
    }

    @Test
    void asking_twice_for_the_same_booking_returns_the_same_order() throws Exception {
        String first = createOrder(5512, "A7X2", 240000).get("orderId").asText();
        String second = createOrder(5512, "A7X2", 240000).get("orderId").asText();

        assertThat(second).as("Pay Now clicked twice").isEqualTo(first);
        assertThat(paymentRepo.count()).isEqualTo(1);
    }

    // ---------- webhooks ----------

    @Test
    void a_genuine_webhook_marks_the_payment_paid_and_announces_it() throws Exception {
        String orderId = createOrder(5512, "A7X2", 240000).get("orderId").asText();

        try (Consumer<String, String> consumer = consumerFromNow()) {
            ResponseEntity<String> res = webhook(captured(orderId, "pay_1", 240000, nowSeconds()), true);
            assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(res.getBody()).isEqualTo("PAID");

            List<ConsumerRecord<String, String>> events = drain(consumer, 1, Duration.ofSeconds(20));
            assertThat(events).hasSize(1);
            JsonNode event = json.readTree(events.get(0).value());
            assertThat(event.get("type").asText()).isEqualTo("PAID");
            assertThat(event.get("userId").asLong()).isEqualTo(5512);
            assertThat(event.get("requestId").asText()).isEqualTo("A7X2");
            assertThat(events.get(0).key()).as("keyed by booking").isEqualTo("5512|A7X2");
        }

        var payment = paymentRepo.findByUserIdAndRequestId(5512L, "A7X2").orElseThrow();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(payment.getRazorpayPaymentId()).isEqualTo("pay_1");
    }

    /** Without this, anyone could POST "paid" and get a free ticket. */
    @Test
    void a_forged_webhook_is_rejected_and_announces_nothing() throws Exception {
        String orderId = createOrder(5512, "A7X2", 240000).get("orderId").asText();

        try (Consumer<String, String> consumer = consumerFromNow()) {
            ResponseEntity<String> res = webhook(captured(orderId, "pay_1", 240000, nowSeconds()), false);

            assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(drain(consumer, 1, Duration.ofSeconds(3))).as("nothing announced").isEmpty();
        }
        assertThat(paymentRepo.findByUserIdAndRequestId(5512L, "A7X2").orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.CREATED);
    }

    /** Razorpay says duplicates are expected. One payment, one announcement. */
    @Test
    void the_same_webhook_twice_announces_once() throws Exception {
        String orderId = createOrder(5512, "A7X2", 240000).get("orderId").asText();
        String body = captured(orderId, "pay_1", 240000, nowSeconds());

        try (Consumer<String, String> consumer = consumerFromNow()) {
            assertThat(webhook(body, true).getBody()).isEqualTo("PAID");
            assertThat(webhook(body, true).getBody()).isEqualTo("DUPLICATE");

            assertThat(drain(consumer, 2, Duration.ofSeconds(5))).as("announced once").hasSize(1);
        }
    }

    @Test
    void a_webhook_for_an_unknown_order_is_answered_and_ignored() throws Exception {
        try (Consumer<String, String> consumer = consumerFromNow()) {
            ResponseEntity<String> res = webhook(captured("order_nobody", "pay_9", 100, nowSeconds()), true);

            assertThat(res.getStatusCode()).as("200, so Razorpay stops retrying").isEqualTo(HttpStatus.OK);
            assertThat(res.getBody()).isEqualTo("UNKNOWN_ORDER");
            assertThat(drain(consumer, 1, Duration.ofSeconds(3))).isEmpty();
        }
    }

    @Test
    void a_payment_for_the_wrong_amount_is_not_confirmed() throws Exception {
        String orderId = createOrder(5512, "A7X2", 240000).get("orderId").asText();

        try (Consumer<String, String> consumer = consumerFromNow()) {
            ResponseEntity<String> res = webhook(captured(orderId, "pay_1", 100, nowSeconds()), true);

            assertThat(res.getBody()).isEqualTo("AMOUNT_MISMATCH");
            assertThat(drain(consumer, 1, Duration.ofSeconds(3))).isEmpty();
        }
        assertThat(paymentRepo.findByUserIdAndRequestId(5512L, "A7X2").orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.CREATED);
    }

    /**
     * The signature is over the exact bytes sent. Same JSON with different spacing
     * is a different message — which is why the body must be checked raw, before
     * anything re-parses it.
     */
    @Test
    void the_signature_is_checked_against_the_exact_bytes_sent() throws Exception {
        String orderId = createOrder(5512, "A7X2", 240000).get("orderId").asText();
        String compact = captured(orderId, "pay_1", 240000, nowSeconds());
        String spaced = compact.replace(",", ", ").replace(":", " : ");

        // signed over the compact form, but the spaced form is what gets sent
        ResponseEntity<String> mismatched = post(spaced, signature.sign(compact));
        assertThat(mismatched.getStatusCode()).as("one space different, signature fails")
                .isEqualTo(HttpStatus.BAD_REQUEST);

        // signed over exactly what is sent, spacing and all
        ResponseEntity<String> exact = post(spaced, signature.sign(spaced));
        assertThat(exact.getBody()).isEqualTo("PAID");
    }

    /** From initial.md: judge by when they paid, not when we found out. */
    @Test
    void paid_at_is_when_the_customer_paid_not_when_the_webhook_arrived() throws Exception {
        String orderId = createOrder(5512, "A7X2", 240000).get("orderId").asText();
        long paidFourMinutesAgo = nowSeconds() - 240;

        try (Consumer<String, String> consumer = consumerFromNow()) {
            webhook(captured(orderId, "pay_1", 240000, paidFourMinutesAgo), true);

            JsonNode event = json.readTree(drain(consumer, 1, Duration.ofSeconds(20)).get(0).value());
            assertThat(Instant.parse(event.get("paidAt").asText()))
                    .isEqualTo(Instant.ofEpochSecond(paidFourMinutesAgo));
        }
    }

    /**
     * Same idea as the seat-count contract. booking-service will read this event
     * in 5c, and reads the same file to check its side.
     */
    @Test
    void what_it_publishes_matches_the_shared_contract() throws Exception {
        JsonNode contract = json.readTree(Path.of("../contracts/payment-event.json").toFile());
        String orderId = createOrder(5512, "A7X2", 240000).get("orderId").asText();

        try (Consumer<String, String> consumer = consumerFromNow()) {
            webhook(captured(orderId, "pay_1", 240000, nowSeconds()), true);
            JsonNode published = json.readTree(drain(consumer, 1, Duration.ofSeconds(20)).get(0).value());

            contract.fieldNames().forEachRemaining(field -> {
                assertThat(published.has(field))
                        .as("payment-service publishes '%s', which booking-service will read", field).isTrue();
                assertThat(published.get(field).getNodeType())
                        .as("'%s' has the type booking-service expects", field)
                        .isEqualTo(contract.get(field).getNodeType());
            });
        }
    }

    // ---------- helpers ----------

    private JsonNode createOrder(long userId, String requestId, long amountPaise) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String body = """
                {"userId":%d,"requestId":"%s","amountPaise":%d}""".formatted(userId, requestId, amountPaise);
        ResponseEntity<String> res = http.postForEntity("/internal/orders", new HttpEntity<>(body, headers), String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        return json.readTree(res.getBody());
    }

    /** Razorpay's payment.captured shape — the fields that matter, nested the same way. */
    private String captured(String orderId, String paymentId, long amountPaise, long createdAtSeconds) {
        return """
                {"entity":"event","event":"payment.captured","contains":["payment"],\
                "payload":{"payment":{"entity":{"id":"%s","entity":"payment","amount":%d,\
                "currency":"INR","status":"captured","order_id":"%s","created_at":%d}}},\
                "created_at":%d}""".formatted(paymentId, amountPaise, orderId, createdAtSeconds, createdAtSeconds);
    }

    private ResponseEntity<String> webhook(String body, boolean signedCorrectly) {
        return post(body, signedCorrectly ? signature.sign(body) : "0".repeat(64));
    }

    private ResponseEntity<String> post(String body, String sig) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Razorpay-Signature", sig);
        return http.postForEntity("/webhooks/razorpay", new HttpEntity<>(body, headers), String.class);
    }

    private long nowSeconds() {
        return Instant.now().getEpochSecond();
    }

    private Consumer<String, String> consumerFromNow() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        Consumer<String, String> consumer = new KafkaConsumer<>(props);
        List<TopicPartition> partitions = consumer.partitionsFor(KafkaConfig.PAYMENT_EVENTS).stream()
                .map(p -> new TopicPartition(p.topic(), p.partition())).toList();
        consumer.assign(partitions);
        consumer.seekToEnd(partitions);
        partitions.forEach(consumer::position);
        return consumer;
    }

    private List<ConsumerRecord<String, String>> drain(Consumer<String, String> consumer, int expected, Duration timeout) {
        List<ConsumerRecord<String, String>> out = new ArrayList<>();
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline && out.size() < expected) {
            consumer.poll(Duration.ofMillis(200)).forEach(out::add);
        }
        return out;
    }
}
