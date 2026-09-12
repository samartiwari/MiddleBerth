package com.middleberth.gateway.auth;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Signing up and logging in.
 *
 * BCrypt is deliberately slow — around a tenth of a second — because that is
 * what makes guessing passwords expensive. On a reactive server that is a
 * problem of its own: a tenth of a second on the event loop stalls every other
 * request in flight. So the hashing runs on boundedElastic, a pool meant for
 * exactly this, and the event loop stays free.
 *
 * Both failures answer identically. "No such account" and "wrong password" as
 * separate answers tell a stranger which email addresses are registered here.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AuthService {

    private final UserRepository users;
    private final PasswordEncoder passwords;
    private final LoginThrottle throttle;

    public Mono<Long> signUp(Credentials credentials, String ip) {
        String email = normalise(credentials.email());

        return throttle.tooManySignups(ip)
                .flatMap(tooMany -> tooMany
                        ? Mono.error(new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                                "too many accounts from here, try later"))
                        : hash(credentials.password()))
                .flatMap(hash -> users.save(new AppUser(email, hash)))
                .map(AppUser::getId)
                // The UNIQUE constraint is what decides, not a lookup first.
                .onErrorMap(DataIntegrityViolationException.class,
                        e -> new ResponseStatusException(HttpStatus.CONFLICT,
                                "that email already has an account"));
    }

    public Mono<Long> logIn(Credentials credentials) {
        String email = normalise(credentials.email());

        return throttle.lockedOut(email)
                .flatMap(locked -> locked
                        ? Mono.error(new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                                "too many failed attempts, try again later"))
                        : users.findByEmail(email))
                .flatMap(user -> matches(credentials.password(), user.getPasswordHash())
                        .flatMap(ok -> ok
                                ? throttle.clearFailures(email).thenReturn(user.getId())
                                : refuse(email)))
                // No such account: still record it and still answer the same way.
                .switchIfEmpty(refuse(email));
    }

    private Mono<Long> refuse(String email) {
        return throttle.recordFailure(email)
                .then(Mono.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                        "email or password is wrong")));
    }

    private Mono<String> hash(String password) {
        return Mono.fromCallable(() -> passwords.encode(password))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private Mono<Boolean> matches(String password, String hash) {
        return Mono.fromCallable(() -> passwords.matches(password, hash))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /** Nobody should fail to log in because they capitalised their own address. */
    private static String normalise(String email) {
        return email.trim().toLowerCase();
    }
}
