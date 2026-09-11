package com.middleberth.payment.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.middleberth.payment.dto.RazorpayWebhook;
import com.middleberth.payment.gateway.WebhookSignature;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class WebhookService {

    private final WebhookSignature signature;
    private final ObjectMapper objectMapper;
    private final CapturedPaymentHandler captured;

    /**
     * Signature first, on the raw bytes — before parsing, before anything else.
     * An unsigned request does not get to cost us a database query.
     */
    public WebhookOutcome handle(String rawBody, String receivedSignature) {
        if (!signature.isValid(rawBody, receivedSignature)) {
            return WebhookOutcome.REJECTED;
        }

        RazorpayWebhook webhook;
        try {
            webhook = objectMapper.readValue(rawBody, RazorpayWebhook.class);
        } catch (Exception e) {
            return WebhookOutcome.REJECTED;
        }

        if (!"payment.captured".equals(webhook.event())) {
            return WebhookOutcome.IGNORED;
        }
        return captured.captured(webhook.payload().payment().entity());
    }
}
