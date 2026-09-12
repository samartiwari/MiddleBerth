package com.middleberth.notification.repository;

import com.middleberth.notification.domain.SentMail;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;

public interface SentMailRepository extends JpaRepository<SentMail, Long> {

    boolean existsByUserIdAndRequestIdAndType(Long userId, String requestId, String type);

    /**
     * Anything older than Kafka's own memory. A duplicate cannot arrive after the
     * message it would duplicate has expired from the topic.
     */
    int deleteBySentAtBefore(OffsetDateTime cutoff);
}
