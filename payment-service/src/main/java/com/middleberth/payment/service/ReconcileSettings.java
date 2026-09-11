package com.middleberth.payment.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * The safety net for webhooks that never arrive.
 *
 * minAge  — leave a new order alone this long; the webhook usually wins, and there
 *           is no point asking the gateway about a payment the customer is still typing
 * maxAge  — stop asking after this; an order nobody paid for in half an hour has
 *           been abandoned, and its hold was released long ago
 */
@ConfigurationProperties(prefix = "middleberth.reconcile")
public record ReconcileSettings(Duration interval, Duration minAge, Duration maxAge) {
}
