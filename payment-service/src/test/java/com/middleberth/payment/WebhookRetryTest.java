package com.middleberth.payment;

import com.middleberth.payment.domain.PaymentStatus;
import com.middleberth.payment.gateway.WebhookSignature;
import com.middleberth.payment.kafka.PaymentEventPublisher;
import com.middleberth.payment.repository.PaymentRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.*;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * The one failure that matters most in this service: a customer paid, and Kafka
 * was unreachable at the moment the webhook arrived.
 *
 * The payment must NOT be marked paid in that case — the webhook has to fail, so
 * Razorpay retries it, and the retry is what finally delivers the ticket.
 *
 * Its own class because spying on the publisher changes the Spring context. That
 * costs one extra set of containers, which is worth it for the money path.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WebhookRetryTest {

    @Autowired TestRestTemplate http;
    @Autowired PaymentRepository paymentRepo;
    @Autowired WebhookSignature signature;
    @MockitoSpyBean PaymentEventPublisher publisher;

    @Test
    void if_kafka_cannot_take_the_event_the_webhook_fails_and_the_retry_delivers_it() {
        paymentRepo.deleteAllInBatch();
        String orderId = createOrder();
        String body = captured(orderId);

        // Kafka is down for the first delivery, back for the second
        doThrow(new IllegalStateException("broker unreachable"))
                .doCallRealMethod()
                .when(publisher).publishAndWait(any());

        ResponseEntity<String> first = webhook(body);
        assertThat(first.getStatusCode().is5xxServerError())
                .as("fail, so Razorpay retries").isTrue();
        assertThat(paymentRepo.findByUserIdAndRequestId(5512L, "A7X2").orElseThrow().getStatus())
                .as("NOT marked paid — otherwise the retry would skip it")
                .isEqualTo(PaymentStatus.CREATED);

        ResponseEntity<String> retry = webhook(body);   // Razorpay, some time later
        assertThat(retry.getBody()).isEqualTo("PAID");
        assertThat(paymentRepo.findByUserIdAndRequestId(5512L, "A7X2").orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.PAID);

        verify(publisher, times(2)).publishAndWait(any());   // failed once, then delivered
    }

    private String createOrder() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String res = http.postForObject("/internal/orders",
                new HttpEntity<>("{\"userId\":5512,\"requestId\":\"A7X2\",\"amountPaise\":240000}", headers),
                String.class);
        return res.replaceAll(".*\"orderId\":\"([^\"]+)\".*", "$1");
    }

    private String captured(String orderId) {
        long now = Instant.now().getEpochSecond();
        return """
                {"event":"payment.captured","payload":{"payment":{"entity":{"id":"pay_1",\
                "amount":240000,"currency":"INR","status":"captured","order_id":"%s",\
                "created_at":%d}}}}""".formatted(orderId, now);
    }

    private ResponseEntity<String> webhook(String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Razorpay-Signature", signature.sign(body));
        return http.postForEntity("/webhooks/razorpay", new HttpEntity<>(body, headers), String.class);
    }
}
