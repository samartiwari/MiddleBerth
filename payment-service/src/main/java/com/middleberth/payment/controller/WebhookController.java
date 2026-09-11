package com.middleberth.payment.controller;

import com.middleberth.payment.service.WebhookOutcome;
import com.middleberth.payment.service.WebhookService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Where Razorpay tells us a payment went through. Razorpay calls us — this is an
 * inbound call, a webhook.
 *
 * No login here: Razorpay has no token. It is trusted because of the signature,
 * not because of who is calling.
 */
@RestController
@RequiredArgsConstructor
public class WebhookController {

    private final WebhookService webhookService;

    /**
     * The body is taken as a String — the raw bytes, exactly as sent. The signature
     * is over those bytes, so it must be checked before anything re-parses them.
     *
     * Any 2xx tells Razorpay "stop, we have it". Anything else makes it retry. So
     * an exception (Kafka unreachable) becomes a 500 and gets retried, while a
     * forged or unknown one is answered so that it stops.
     */
    @PostMapping("/webhooks/razorpay")
    public ResponseEntity<String> razorpay(@RequestBody String rawBody,
                                           @RequestHeader(value = "X-Razorpay-Signature", required = false) String signature) {
        WebhookOutcome outcome = webhookService.handle(rawBody, signature);
        HttpStatus status = outcome == WebhookOutcome.REJECTED ? HttpStatus.BAD_REQUEST : HttpStatus.OK;
        return ResponseEntity.status(status).body(outcome.name());
    }
}
