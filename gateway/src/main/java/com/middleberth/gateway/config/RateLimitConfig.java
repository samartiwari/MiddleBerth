package com.middleberth.gateway.config;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.security.Principal;

@Configuration
public class RateLimitConfig {

    /**
     * Rate limit buckets are keyed by the user id inside the token — not by IP.
     *
     * So everyone behind one college wifi or one Jio NAT gets their own bucket,
     * and one person hammering Book cannot use up anyone else's.
     */
    @Bean
    KeyResolver userKeyResolver() {
        return exchange -> exchange.getPrincipal().map(Principal::getName);
    }
}
