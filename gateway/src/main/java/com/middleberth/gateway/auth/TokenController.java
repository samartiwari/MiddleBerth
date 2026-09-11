package com.middleberth.gateway.auth;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;

/**
 * DEMO LOGIN. There is no password — ask for a user id, get a signed token.
 *
 * MiddleBerth has no user service on purpose (initial.md: login is handled at the
 * gateway), and real sign-up, passwords and identity are out of scope. What this
 * project demonstrates is what happens AFTER login: the token checked at the edge,
 * the user id passed on as a header the client cannot forge, and per-user rate
 * limits.
 *
 * It also makes load testing trivial — 5,000 fake users is 5,000 calls here.
 *
 * In anything real this endpoint is replaced by an identity provider.
 */
@RestController
@RequiredArgsConstructor
public class TokenController {

    private final JwtEncoder encoder;

    @Value("${middleberth.jwt.ttl-minutes}")
    private long ttlMinutes;

    @PostMapping("/auth/token")
    public Mono<TokenResponse> token(@RequestBody TokenRequest request) {
        if (request.userId() == null || request.userId() <= 0) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "userId must be positive"));
        }

        Instant now = Instant.now();
        Duration ttl = Duration.ofMinutes(ttlMinutes);

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("middleberth-gateway")
                .subject(String.valueOf(request.userId()))   // becomes X-User-Id downstream
                .issuedAt(now)
                .expiresAt(now.plus(ttl))
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();

        String token = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return Mono.just(new TokenResponse(token, ttl.toSeconds()));
    }
}
