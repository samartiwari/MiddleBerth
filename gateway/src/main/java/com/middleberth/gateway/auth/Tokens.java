package com.middleberth.gateway.auth;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * Mints the pass, and nothing else.
 *
 * One place, because two different doors now issue them: a real login, and the
 * demo endpoint the load test uses. The token they hand out is identical — HS256,
 * signed with a secret only this gateway knows, the user id as the subject, and
 * an hour to live.
 *
 * Nothing private goes in here. A JWT's payload is base64, not encryption:
 * anybody holding the token can read it. A user id is all it needs to carry.
 */
@Component
@RequiredArgsConstructor
public class Tokens {

    private final JwtEncoder encoder;

    @Value("${middleberth.jwt.ttl-minutes}")
    private long ttlMinutes;

    public TokenResponse forUser(long userId) {
        Instant now = Instant.now();
        Duration ttl = Duration.ofMinutes(ttlMinutes);

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("middleberth-gateway")
                .subject(String.valueOf(userId))       // becomes X-User-Id downstream
                .issuedAt(now)
                .expiresAt(now.plus(ttl))
                .build();

        String token = encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
        return new TokenResponse(token, ttl.toSeconds());
    }
}
