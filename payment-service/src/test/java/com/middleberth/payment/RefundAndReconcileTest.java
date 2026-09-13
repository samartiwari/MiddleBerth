package com.middleberth.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.middleberth.payment.domain.PaymentStatus;
import com.middleberth.payment.dto.RefundRequest;
import com.middleberth.payment.gateway.StubPaymentGateway;
import com.middleberth.payment.gateway.WebhookSignature;
import com.middleberth.payment.kafka.KafkaConfig;
import com.middleberth.payment.kafka.RefundRequestsConfig;
import com.middleberth.payment.repository.PaymentRepository;
import com.middleberth.payment.service.NotCapturedYetException;
import com.middleberth.payment.service.ReconciliationJob;
import com.middleberth.payment.service.RefundHandler;
import com.middleberth.payment.service.PurgeJob;
import com.middleberth.payment.service.RefundOutcome;
import org.apache.kafka.clients.consumer.*;
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
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 5d, payment-service's half: giving money back, and finding payments whose
 * webhook never arrived.
 *
 * The refund is asked for over Kafka by booking-service, because a refund is not
 * urgent and must not be lost — exactly the case a queue is for.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RefundAndReconcileTest {

    @Autowired TestRestTemplate http;
    @Autowired PaymentRepository paymentRepo;
    @Autowired WebhookSignature signature;
    @Autowired StubPaymentGateway gateway;
    @Autowired RefundHandler refunds;
    @Autowired ReconciliationJob reconciliation;
    @Autowired KafkaConnectionDetails kafka;
    @Autowired PurgeJob purgeJob;
    @Autowired JdbcTemplate jdbc;

    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void wipe() {
        paymentRepo.deleteAllInBatch();
        gateway.reset();
    }

    // ---------- refunds ----------

    @Test
    void a_refund_request_gives_the_money_back() throws Exception {
        String orderId = createOrder(5512, "A7X2", 240000);
        pay(orderId, "pay_1", 240000);

        ask(refundFor(5512, "A7X2", orderId, "pay_1", 240000, "PAID_AFTER_DEADLINE"));

        var payment = awaitStatus(5512, "A7X2", PaymentStatus.REFUNDED);
        assertThat(payment.getRefundId()).startsWith("rfnd_");
        assertThat(payment.getRefundedAt()).isNotNull();
        assertThat(gateway.refundsPaidOut()).isEqualTo(1);
    }

    /**
     * booking-service asks at least once, and asks again if its own listener retries.
     * The gateway is only allowed to pay out once.
     */
    @Test
    void the_same_refund_request_twice_pays_out_once() throws Exception {
        String first = createOrder(5512, "A7X2", 240000);
        pay(first, "pay_1", 240000);
        String refund = refundFor(5512, "A7X2", first, "pay_1", 240000, "NOTHING_LEFT");

        ask(refund);
        ask(refund);

        // a different booking behind the duplicate: once its refund is done, both
        // copies of the first one have certainly been through the listener
        String second = createOrder(77, "B9", 90000);
        pay(second, "pay_2", 90000);
        ask(refundFor(77, "B9", second, "pay_2", 90000, "NOTHING_LEFT"));
        awaitStatus(77, "B9", PaymentStatus.REFUNDED);

        assertThat(awaitStatus(5512, "A7X2", PaymentStatus.REFUNDED).getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(gateway.refundsPaidOut()).as("one payout per payment, not per request").isEqualTo(2);
    }

    /**
     * The refund request can overtake our own record of the payment — both travel
     * over Kafka. Refunding something we have not recorded as captured would be
     * guessing, so it throws, and the listener tries again a second later.
     */
    @Test
    void a_refund_asked_for_before_the_payment_is_recorded_is_retried_not_dropped() throws Exception {
        String orderId = createOrder(5512, "A7X2", 240000);
        RefundRequest request = json.readValue(
                refundFor(5512, "A7X2", orderId, "pay_1", 240000, "NOTHING_LEFT"), RefundRequest.class);

        assertThatThrownBy(() -> refunds.refund(request)).isInstanceOf(NotCapturedYetException.class);
        assertThat(gateway.refundsPaidOut()).as("nothing paid out on a guess").isZero();

        pay(orderId, "pay_1", 240000);                    // the webhook catches up

        assertThat(refunds.refund(request)).isEqualTo(RefundOutcome.REFUNDED);
        assertThat(gateway.refundsPaidOut()).isEqualTo(1);
    }

    /** Razorpay can redeliver a webhook days later. It must not undo the refund. */
    @Test
    void a_webhook_that_turns_up_after_the_refund_does_not_undo_it() throws Exception {
        String orderId = createOrder(5512, "A7X2", 240000);
        String body = capturedBody(orderId, "pay_1", 240000);
        assertThat(webhook(body).getBody()).isEqualTo("PAID");
        refunds.refund(json.readValue(
                refundFor(5512, "A7X2", orderId, "pay_1", 240000, "NOTHING_LEFT"), RefundRequest.class));

        assertThat(webhook(body).getBody()).isEqualTo("DUPLICATE");

        assertThat(paymentRepo.findByUserIdAndRequestId(5512L, "A7X2").orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.REFUNDED);
    }

    /** booking-service's half of this contract is checked in its own LatePaymentTest. */
    @Test
    void the_shared_contract_is_understood() throws Exception {
        RefundRequest request = json.readValue(
                Files.readString(Path.of("../contracts/refund-request.json")), RefundRequest.class);

        assertThat(request.userId()).isEqualTo(5512L);
        assertThat(request.requestId()).isEqualTo("A7X2");
        assertThat(request.orderId()).isNotBlank();
        assertThat(request.paymentId()).isNotBlank();
        assertThat(request.amountPaise()).isEqualTo(240000L);
        assertThat(request.reason()).isNotBlank();
    }

    // ---------- the webhook that never came ----------

    @Test
    void a_payment_whose_webhook_never_arrived_is_found_by_the_safety_net() throws Exception {
        String orderId = createOrder(5512, "A7X2", 240000);
        long paidAt = Instant.now().getEpochSecond();
        gateway.simulatePaidButWebhookLost(orderId, "pay_lost", 240000, paidAt);

        try (Consumer<String, String> consumer = consumerFromNow()) {
            assertThat(reconciliation.reconcile(Instant.now().plus(Duration.ofMinutes(1)))).isEqualTo(1);

            JsonNode event = json.readTree(drain(consumer, 1, Duration.ofSeconds(20)).get(0).value());
            assertThat(event.get("type").asText()).as("announced exactly as a webhook would be").isEqualTo("PAID");
            assertThat(event.get("requestId").asText()).isEqualTo("A7X2");
            assertThat(Instant.parse(event.get("paidAt").asText())).isEqualTo(Instant.ofEpochSecond(paidAt));
        }

        var payment = paymentRepo.findByUserIdAndRequestId(5512L, "A7X2").orElseThrow();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(payment.getRazorpayPaymentId()).isEqualTo("pay_lost");
    }

    /** The webhook is the fast path. Do not go asking about an order seconds old. */
    @Test
    void an_order_that_was_just_created_is_left_to_the_webhook() throws Exception {
        String orderId = createOrder(5512, "A7X2", 240000);
        gateway.simulatePaidButWebhookLost(orderId, "pay_lost", 240000, Instant.now().getEpochSecond());

        assertThat(reconciliation.reconcile(Instant.now())).isZero();

        assertThat(paymentRepo.findByUserIdAndRequestId(5512L, "A7X2").orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.CREATED);
    }

    /** And the webhook arriving afterwards is just a duplicate. */
    @Test
    void the_webhook_arriving_after_the_safety_net_changes_nothing() throws Exception {
        String orderId = createOrder(5512, "A7X2", 240000);
        gateway.simulatePaidButWebhookLost(orderId, "pay_lost", 240000, Instant.now().getEpochSecond());
        reconciliation.reconcile(Instant.now().plus(Duration.ofMinutes(1)));

        assertThat(webhook(capturedBody(orderId, "pay_lost", 240000)).getBody()).isEqualTo("DUPLICATE");
    }

    /**
     * One row per Pay Now, every day, for ever — unless something removes them.
     * A week, matching the travel dates booking-service keeps, so nothing can ask
     * about a payment whose booking is already gone.
     */
    @Test
    void payments_older_than_the_window_are_deleted() throws Exception {
        createOrder(5512, "OLD", 240000);
        createOrder(77, "RECENT", 90000);
        // created_at is set by the database, so age it by hand
        jdbc.execute("UPDATE payment SET created_at = now() - interval '30 days' "
                + "WHERE request_id = 'OLD'");

        int removed = purgeJob.purgeBefore(OffsetDateTime.now().minusDays(7));

        assertThat(removed).isEqualTo(1);
        assertThat(paymentRepo.findByUserIdAndRequestId(5512L, "OLD")).as("a month gone").isEmpty();
        assertThat(paymentRepo.findByUserIdAndRequestId(77L, "RECENT")).as("still inside the week").isPresent();
    }

    /**
     * A passenger who changes their mind gets the TICKET back, not the convenience
     * fee.
     *
     * That fee is already spent: the gateway took its cut out of the payment on the
     * way in and does not return it. Refunding the whole 247,200 would mean paying
     * out money that never arrived — which is exactly what the first live payment
     * discovered, when Razorpay refused a full refund because the account did not
     * hold that much.
     */
    @Test
    void a_passenger_cancelling_is_refunded_the_fare_but_not_the_fee() throws Exception {
        String orderId = createOrder(5512, "A7X2", 247_200, 240_000);   // 2,400 fare + 3%
        pay(orderId, "pay_1", 247_200);

        ask(cancellationRefundFor(5512, "A7X2"));
        awaitStatus(5512, "A7X2", PaymentStatus.REFUNDED);

        assertThat(gateway.refundedAmountFor("pay_1"))
                .as("the fare, not the 7,200 paise that paid the gateway")
                .isEqualTo(240_000);
    }

    /**
     * When it is OUR failure, they get all of it — fee included.
     *
     * Money was taken for a berth we could not give. Keeping a service charge for a
     * service never delivered would be indefensible, so the company swallows the
     * gateway's cut instead.
     */
    @Test
    void money_we_cannot_honour_is_returned_in_full_fee_included() throws Exception {
        String orderId = createOrder(77, "B9", 247_200, 240_000);
        pay(orderId, "pay_2", 247_200);

        ask(refundFor(77, "B9", orderId, "pay_2", 247_200, "PAID_AFTER_DEADLINE"));
        awaitStatus(77, "B9", PaymentStatus.REFUNDED);

        assertThat(gateway.refundedAmountFor("pay_2"))
                .as("everything, including the part the gateway kept")
                .isEqualTo(247_200);
    }

    // ---------- helpers ----------

    private String createOrder(long userId, String requestId, long amountPaise) throws Exception {
        return createOrder(userId, requestId, amountPaise, amountPaise);
    }

    /** charged vs refundable differ by the convenience fee. */
    private String createOrder(long userId, String requestId, long amountPaise, long refundablePaise)
            throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String body = """
                {"userId":%d,"requestId":"%s","amountPaise":%d,"refundablePaise":%d}"""
                .formatted(userId, requestId, amountPaise, refundablePaise);
        ResponseEntity<String> res = http.postForEntity("/internal/orders", new HttpEntity<>(body, headers), String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        return json.readTree(res.getBody()).get("orderId").asText();
    }

    private void pay(String orderId, String paymentId, long amountPaise) {
        assertThat(webhook(capturedBody(orderId, paymentId, amountPaise)).getBody()).isEqualTo("PAID");
    }

    private String capturedBody(String orderId, String paymentId, long amountPaise) {
        long now = Instant.now().getEpochSecond();
        return """
                {"event":"payment.captured","payload":{"payment":{"entity":{"id":"%s","amount":%d,\
                "currency":"INR","status":"captured","order_id":"%s","created_at":%d}}}}"""
                .formatted(paymentId, amountPaise, orderId, now);
    }

    private ResponseEntity<String> webhook(String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Razorpay-Signature", signature.sign(body));
        return http.postForEntity("/webhooks/razorpay", new HttpEntity<>(body, headers), String.class);
    }

    /**
     * What booking-service actually sends for a cancellation: no order id, no
     * payment id, no amount. It never stored an order id and does not need to —
     * payment-service finds the payment by (user, booking) and knows what it took.
     */
    private String cancellationRefundFor(long userId, String requestId) {
        return """
                {"userId":%d,"requestId":"%s","orderId":null,"paymentId":null,\
                "amountPaise":0,"reason":"CANCELLED_BY_PASSENGER"}"""
                .formatted(userId, requestId);
    }

    private String refundFor(long userId, String requestId, String orderId, String paymentId,
                             long amountPaise, String reason) {
        return """
                {"userId":%d,"requestId":"%s","orderId":"%s","paymentId":"%s","amountPaise":%d,"reason":"%s"}"""
                .formatted(userId, requestId, orderId, paymentId, amountPaise, reason);
    }

    /** Plays booking-service. */
    private void ask(String refundRequest) {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        try (Producer<String, String> producer = new KafkaProducer<>(props)) {
            String key = json.readTree(refundRequest).get("userId").asText()
                    + "|" + json.readTree(refundRequest).get("requestId").asText();
            producer.send(new ProducerRecord<>(RefundRequestsConfig.REFUND_REQUESTS, key, refundRequest));
            producer.flush();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private com.middleberth.payment.domain.Payment awaitStatus(long userId, String requestId, PaymentStatus expected) {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        com.middleberth.payment.domain.Payment payment = null;
        while (System.nanoTime() < deadline) {
            payment = paymentRepo.findByUserIdAndRequestId(userId, requestId).orElse(null);
            if (payment != null && payment.getStatus() == expected) return payment;
            try { Thread.sleep(50); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
        }
        assertThat(payment).isNotNull();
        assertThat(payment.getStatus()).as("payment %s/%s", userId, requestId).isEqualTo(expected);
        return payment;
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
