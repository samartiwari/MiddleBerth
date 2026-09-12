package com.middleberth.notification.repository;

import com.middleberth.notification.domain.SentMail;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SentMailRepository extends JpaRepository<SentMail, Long> {

    boolean existsByUserIdAndRequestIdAndType(Long userId, String requestId, String type);
}
