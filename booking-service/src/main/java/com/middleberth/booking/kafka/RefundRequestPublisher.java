package com.middleberth.booking.kafka;

import com.middleberth.booking.dto.RefundRequest;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
@RequiredArgsConstructor
public class RefundRequestPublisher {

    public static final String REFUND_REQUESTS = "refund-requests";

    private final KafkaTemplate<String, Object> kafka;

    /**
     * Waits for the broker, and throws if it can't take it. This runs inside the
     * payment-events listener, so a throw means that paid event is retried — and
     * the refund is asked for again. Never fire-and-forget with someone's money.
     * Asking twice is harmless: payment-service refunds each payment once.
     */
    public void publishAndWait(RefundRequest request) {
        String key = request.userId() + "|" + request.requestId();
        try {
            kafka.send(REFUND_REQUESTS, key, request).get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("Could not request a refund for " + key, e);
        }
    }

    @Configuration
    static class Topic {
        /** Declared on both sides — whoever touches a topic first decides its partitions. */
        @Bean
        NewTopic refundRequests() {
            return new NewTopic(REFUND_REQUESTS, 15, (short) 1);
        }
    }
}
