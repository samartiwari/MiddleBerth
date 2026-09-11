package com.middleberth.payment.gateway;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * Proves a webhook really came from Razorpay.
 *
 * Razorpay computes HMAC-SHA256 of the RAW request body, keyed with the webhook
 * secret, and sends it as X-Razorpay-Signature. We compute the same thing and
 * compare. Without this, anyone could POST "booking A7X2 is paid" and get a free
 * ticket.
 *
 * The raw body, exactly as it arrived — not JSON that Spring has parsed and
 * written back out. Re-serialising can change a single space or the order of
 * keys, and then the signature no longer matches.
 */
@Component
public class WebhookSignature {

    private final byte[] secret;

    public WebhookSignature(@Value("${middleberth.razorpay.webhook-secret}") String secret) {
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }

    public boolean isValid(String rawBody, String receivedSignature) {
        if (receivedSignature == null || receivedSignature.isBlank()) {
            return false;
        }
        byte[] expected = sign(rawBody).getBytes(StandardCharsets.UTF_8);
        byte[] received = receivedSignature.getBytes(StandardCharsets.UTF_8);
        // Constant-time comparison. A plain equals() returns early on the first
        // wrong character, and the time it takes leaks how much of a guess was right.
        return MessageDigest.isEqual(expected, received);
    }

    public String sign(String rawBody) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(rawBody.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }
}
