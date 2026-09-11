package com.middleberth.payment.service;

import com.middleberth.payment.domain.Payment;
import com.middleberth.payment.dto.CreateOrderRequest;
import com.middleberth.payment.dto.CreateOrderResponse;
import com.middleberth.payment.gateway.GatewayOrder;
import com.middleberth.payment.gateway.PaymentGateway;
import com.middleberth.payment.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
@RequiredArgsConstructor
public class PaymentService {

    static final String CURRENCY = "INR";

    private final PaymentRepository paymentRepo;
    private final PaymentGateway gateway;

    /**
     * One order per booking. Asking twice returns the same order.
     *
     * Deliberately NOT one big transaction. Creating the order is a network call
     * to Razorpay (~100ms in real life), and holding a database connection open
     * while waiting on someone else's server is how a connection pool of 10 runs
     * dry — the open-in-view lesson again. So: call the gateway with no
     * connection held, then save in a short transaction of its own.
     */
    public CreateOrderResponse createOrder(CreateOrderRequest req) {
        Optional<Payment> existing = paymentRepo.findByUserIdAndRequestId(req.userId(), req.requestId());
        if (existing.isPresent()) {
            return response(existing.get());
        }

        GatewayOrder order = gateway.createOrder(req.amountPaise(), CURRENCY,
                "booking " + req.userId() + "/" + req.requestId());

        try {
            return response(paymentRepo.saveAndFlush(Payment.created(
                    order.orderId(), req.userId(), req.requestId(), order.amountPaise(), order.currency())));
        } catch (DataIntegrityViolationException raced) {
            // Two Pay Now clicks at the same instant both reached here. The other one
            // saved first — return its order. The gateway order this call created is
            // left unused; nobody can pay into it, and Razorpay expires it.
            return paymentRepo.findByUserIdAndRequestId(req.userId(), req.requestId())
                    .map(this::response)
                    .orElseThrow(() -> raced);
        }
    }

    private CreateOrderResponse response(Payment p) {
        return new CreateOrderResponse(p.getOrderId(), p.getAmountPaise(), p.getCurrency(), gateway.publicKeyId());
    }
}
