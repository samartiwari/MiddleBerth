package com.middleberth.payment.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.OffsetDateTime;

@Entity
@Table(name = "payment")
@Getter
@NoArgsConstructor
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false, unique = true, length = 40)
    private String orderId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "request_id", nullable = false, length = 40)
    private String requestId;

    @Column(name = "amount_paise", nullable = false)
    private long amountPaise;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 12)
    private PaymentStatus status;

    @Column(name = "razorpay_payment_id", unique = true, length = 40)
    private String razorpayPaymentId;

    @Column(name = "paid_at")
    private Instant paidAt;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    public static Payment created(String orderId, Long userId, String requestId,
                                  long amountPaise, String currency) {
        Payment p = new Payment();
        p.orderId = orderId;
        p.userId = userId;
        p.requestId = requestId;
        p.amountPaise = amountPaise;
        p.currency = currency;
        p.status = PaymentStatus.CREATED;
        return p;
    }

    /**
     * paidAt is when the customer PAID, taken from the gateway — not when the
     * webhook happened to reach us. From initial.md: judge by when they paid, not
     * when we found out. Our lag is not their problem.
     */
    public void markPaid(String razorpayPaymentId, Instant paidAt) {
        this.razorpayPaymentId = razorpayPaymentId;
        this.paidAt = paidAt;
        this.status = PaymentStatus.PAID;
    }

    public boolean isPaid() {
        return status == PaymentStatus.PAID;
    }
}
