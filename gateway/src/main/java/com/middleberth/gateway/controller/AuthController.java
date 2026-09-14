package com.middleberth.gateway.controller;

import com.middleberth.gateway.service.AuthService;
import com.middleberth.gateway.dto.Credentials;
import com.middleberth.gateway.dto.TokenResponse;
import com.middleberth.gateway.service.Tokens;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * The front door: sign up, log in, get a token.
 *
 * The token is exactly the same one this gateway has always issued — same
 * algorithm, same claims, same hour. The only thing that changed is that you now
 * have to prove the identity inside it.
 */
@RestController
@RequiredArgsConstructor
@Tag(name = "Accounts", description = "Get a token for the booking and passenger endpoints.")
public class AuthController {

    private final AuthService auth;
    private final Tokens tokens;

    @PostMapping("/auth/signup")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create an account and get a token",
            description = "Any email, and a password of 8 to 100 characters. The token lasts an hour.")
    public Mono<TokenResponse> signUp(@Valid @RequestBody Credentials credentials,
                                      ServerWebExchange exchange) {
        return auth.signUp(credentials, callerIp(exchange)).map(tokens::forUser);
    }

    @PostMapping("/auth/login")
    @Operation(summary = "Log in and get a token",
            description = "Five wrong passwords lock the account for 15 minutes.")
    public Mono<TokenResponse> logIn(@Valid @RequestBody Credentials credentials) {
        return auth.logIn(credentials).map(tokens::forUser);
    }

    /**
     * Behind nginx every request arrives from nginx, so the real address is in
     * X-Forwarded-For. Only the first entry is worth reading — the rest can be
     * anything the client felt like sending.
     */
    private static String callerIp(ServerWebExchange exchange) {
        String forwarded = exchange.getRequest().getHeaders().getFirst("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        var remote = exchange.getRequest().getRemoteAddress();
        return remote == null ? "unknown" : remote.getAddress().getHostAddress();
    }
}
