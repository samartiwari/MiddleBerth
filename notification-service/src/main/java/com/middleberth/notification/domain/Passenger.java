package com.middleberth.notification.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "passenger")
@Getter
@NoArgsConstructor
public class Passenger {

    @Id
    @Column(name = "user_id")
    private Long userId;

    @Column(name = "email", nullable = false, length = 120)
    private String email;

    public Passenger(Long userId, String email) {
        this.userId = userId;
        this.email = email;
    }
}
