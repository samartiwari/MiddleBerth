package com.middleberth.payment.gateway;

/** What the gateway hands back when an order is created. */
public record GatewayOrder(String orderId, long amountPaise, String currency) {
}
