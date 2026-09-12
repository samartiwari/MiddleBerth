package com.middleberth.gateway.controller;

import com.middleberth.gateway.dto.TokenRequest;
import com.middleberth.gateway.dto.TokenResponse;
import com.middleberth.gateway.service.Tokens;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

/**
 * A TOKEN FOR ANY USER ID YOU NAME. No password, no account, no questions.
 *
 * It only exists for load testing. BCrypt takes about a tenth of a second on
 * purpose, so two thousand virtual users signing up properly would spend minutes
 * hashing passwords — and the thing being measured is seat contention, not
 * BCrypt.
 *
 * Off unless middleberth.auth.demo-tokens=true. It must never be on anywhere
 * real: with it on, anybody can be anybody, which makes every other protection
 * in this gateway pointless.
 */
@RestController
@RequiredArgsConstructor
@ConditionalOnProperty(name = "middleberth.auth.demo-tokens", havingValue = "true")
public class TokenController {

    private final Tokens tokens;

    @PostMapping("/auth/token")
    public Mono<TokenResponse> token(@RequestBody TokenRequest request) {
        if (request.userId() == null || request.userId() <= 0) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "userId must be positive"));
        }
        return Mono.just(tokens.forUser(request.userId()));
    }
}
