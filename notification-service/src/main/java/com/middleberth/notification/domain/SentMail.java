package com.middleberth.notification.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

/** Proof that this exact message has already gone out. */
@Entity
@Table(name = "sent_mail")
@Getter
@NoArgsConstructor
public class SentMail {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "request_id", nullable = false, length = 40)
    private String requestId;

    @Column(name = "type", nullable = false, length = 24)
    private String type;

    @Column(name = "sent_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime sentAt;

    public SentMail(Long userId, String requestId, String type) {
        this.userId = userId;
        this.requestId = requestId;
        this.type = type;
    }
}
