package com.middleberth.booking.repository;

import com.middleberth.booking.domain.OutboxNote;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface OutboxRepository extends JpaRepository<OutboxNote, Long> {

    /**
     * The same trick as claiming a berth. Every pod runs the sending job, and
     * SKIP LOCKED means two pods take two different batches instead of fighting
     * over the same one — so nobody's mail goes out twice because of us.
     */
    @Query(value = """
            SELECT * FROM outbox
            WHERE sent_at IS NULL
            ORDER BY id
            LIMIT :batchSize
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OutboxNote> lockUnsent(@Param("batchSize") int batchSize);
}
