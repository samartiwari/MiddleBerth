package com.middleberth.payment.kafka;

import com.middleberth.payment.dto.RefundRequest;
import com.middleberth.payment.service.RefundHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * booking-service decided a customer must be paid back; this carries it out.
 *
 * Its own group id — payment-service is a different service from the one that
 * published these, so it must not share booking's group.
 */
@Component
@RequiredArgsConstructor
public class RefundRequestConsumer {

    private final RefundHandler refunds;

    @KafkaListener(topics = RefundRequestsConfig.REFUND_REQUESTS,
                   groupId = "payment-service",
                   containerFactory = "refundRequestsFactory")
    public void handle(RefundRequest request) {
        refunds.refund(request);
    }
}
