package com.middleberth.payment.controller;

import com.middleberth.payment.dto.CreateOrderRequest;
import com.middleberth.payment.dto.CreateOrderResponse;
import com.middleberth.payment.service.PaymentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * INTERNAL. Called by booking-service, never by a browser.
 *
 * booking-service is the one that knows whether a booking is still a live hold,
 * who owns it and what it costs — so the user's Pay Now click goes to booking,
 * which checks all that and then asks here for an order. This is the one
 * synchronous service-to-service call in the system (booking -> payment).
 *
 * The gateway has no route to /internal/**, so it cannot be reached from outside.
 */
@RestController
@RequiredArgsConstructor
public class OrderController {

    private final PaymentService paymentService;

    @PostMapping("/internal/orders")
    public CreateOrderResponse create(@Valid @RequestBody CreateOrderRequest request) {
        return paymentService.createOrder(request);
    }
}
