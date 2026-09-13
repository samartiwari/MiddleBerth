package com.middleberth.notification.repository;

import com.middleberth.notification.domain.SentMail;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;

public interface SentMailRepository extends JpaRepository<SentMail, Long> {

    boolean existsByUserIdAndRequestIdAndType(Long userId, String requestId, String type);

    /**
     * Every record for one passenger. Used by the tests to count what THEY caused,
     * rather than everything in the table — a message still in flight from an
     * earlier test lands in the same table and makes a total meaningless.
     */
    long countByUserId(Long userId);

    /**
     * Anything older than Kafka's own memory. A duplicate cannot arrive after the
     * message it would duplicate has expired from the topic.
     */
    int deleteBySentAtBefore(OffsetDateTime cutoff);
}
