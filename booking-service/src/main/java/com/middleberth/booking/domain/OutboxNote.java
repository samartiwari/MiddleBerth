package com.middleberth.booking.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.OffsetDateTime;

/** One message waiting to go out. */
@Entity
@Table(name = "outbox")
@Getter
@NoArgsConstructor
public class OutboxNote {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** userId|requestId — the Kafka key, so one booking's messages stay in order. */
    @Column(name = "booking_key", nullable = false, length = 64)
    private String bookingKey;

    @Column(name = "type", nullable = false, length = 24)
    private String type;

    @Column(name = "payload", nullable = false)
    private String payload;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "sent_at")
    private Instant sentAt;

    public OutboxNote(String bookingKey, String type, String payload) {
        this.bookingKey = bookingKey;
        this.type = type;
        this.payload = payload;
    }

    public void markSent(Instant at) {
        this.sentAt = at;
    }
}
