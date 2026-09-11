package com.middleberth.payment.repository;

import com.middleberth.payment.domain.Payment;
import com.middleberth.payment.domain.PaymentStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    Optional<Payment> findByUserIdAndRequestId(Long userId, String requestId);

    Optional<Payment> findByOrderId(String orderId);

    /** Orders nobody has paid for yet, created within a window — for the reconciliation job. */
    List<Payment> findByStatusAndCreatedAtBetween(PaymentStatus status, OffsetDateTime from, OffsetDateTime to);

    /**
     * Locked, because Razorpay may deliver the same webhook twice at the same
     * moment. The second one waits here, then sees PAID and does nothing.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payment p where p.orderId = :orderId")
    Optional<Payment> lockByOrderId(@Param("orderId") String orderId);
}
