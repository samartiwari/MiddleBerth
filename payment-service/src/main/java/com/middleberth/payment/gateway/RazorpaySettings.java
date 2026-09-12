package com.middleberth.payment.gateway;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * What the live gateway needs to talk to Razorpay.
 *
 * The keys come from the environment and nowhere else. They are not in this
 * repository, not in the image, and not in any file that gets committed.
 */
@ConfigurationProperties(prefix = "middleberth.razorpay")
public record RazorpaySettings(String keyId,
                               String keySecret,
                               String apiUrl,
                               Duration connectTimeout,
                               Duration readTimeout) {
}
