package com.middleberth.gateway.auth;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * A login endpoint with no limit is a password guessing machine.
 *
 * Counted in Redis, per ACCOUNT, not per IP. Guessing is an attack on one
 * account, and an IP limit tight enough to stop it would lock out everyone
 * sharing a college wifi or a mobile carrier's address — the same reason booking
 * is limited per user and search not at all.
 *
 * Signups are capped per IP as well, because that abuse IS one machine making
 * many accounts.
 */
@Component
@RequiredArgsConstructor
public class LoginThrottle {

    private final ReactiveStringRedisTemplate redis;
    private final AuthSettings settings;

    /** Empty if this account may try again, or the number of failures if it may not. */
    Mono<Boolean> lockedOut(String email) {
        return redis.opsForValue().get(failureKey(email))
                .map(count -> Integer.parseInt(count) >= settings.maxLoginFailures())
                .defaultIfEmpty(false);
    }

    Mono<Void> recordFailure(String email) {
        String key = failureKey(email);
        return redis.opsForValue().increment(key)
                .flatMap(count -> count == 1L
                        // Only the first failure sets the clock, so the window is
                        // fifteen minutes from the first wrong guess and not a
                        // window an attacker can keep pushing forward.
                        ? redis.expire(key, settings.lockout()).then()
                        : Mono.empty());
    }

    Mono<Void> clearFailures(String email) {
        return redis.delete(failureKey(email)).then();
    }

    /** True when this address has created as many accounts as it is allowed to. */
    Mono<Boolean> tooManySignups(String ip) {
        String key = "middleberth:signups:" + ip;
        return redis.opsForValue().increment(key)
                .flatMap(count -> count == 1L
                        ? redis.expire(key, settings.signupWindow()).thenReturn(count)
                        : Mono.just(count))
                .map(count -> count > settings.maxSignupsPerIp());
    }

    private static String failureKey(String email) {
        return "middleberth:login-fails:" + email;
    }
}
