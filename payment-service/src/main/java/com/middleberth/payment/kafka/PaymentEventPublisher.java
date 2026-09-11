package com.middleberth.payment.kafka;

import com.middleberth.payment.dto.PaymentEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
@RequiredArgsConstructor
public class PaymentEventPublisher {

    private final KafkaTemplate<String, Object> kafka;

    /**
     * Waits for the broker to confirm, and throws if it cannot.
     *
     * That is the point: if the event cannot be handed to Kafka, the webhook must
     * FAIL, so Razorpay retries it. Razorpay's own retries are the delivery
     * guarantee here — fire-and-forget would tell Razorpay "got it" and then lose
     * the one message that turns someone's money into a ticket.
     *
     * Waiting a few milliseconds is fine on this path. Webhooks arrive once per
     * payment, not lakhs at a time.
     */
    public void publishAndWait(PaymentEvent event) {
        String key = event.userId() + "|" + event.requestId();   // one booking's events stay in order
        try {
            kafka.send(KafkaConfig.PAYMENT_EVENTS, key, event).get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("Could not publish payment event for " + key, e);
        }
    }
}
