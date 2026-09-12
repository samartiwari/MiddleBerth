package com.middleberth.gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
public class PasswordConfig {

    /**
     * BCrypt, at the default strength of 10 — about a tenth of a second per hash.
     *
     * That slowness is the entire feature. A fast hash lets somebody who steals
     * the database try billions of guesses; this one lets them try a handful.
     * It also salts every password, so two people who choose the same one get
     * different hashes and cracking one does not crack the other.
     */
    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
