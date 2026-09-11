package com.middleberth.gateway.filter;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.security.Principal;
import java.util.Optional;

/**
 * Tells the services behind the gateway who is calling.
 *
 * Two jobs, both on every request:
 *
 *   1. REMOVE any X-User-Id the client sent. Otherwise anyone could put
 *      "X-User-Id: 999" on a request and act as user 999.
 *   2. ADD X-User-Id from the checked token, if there is one.
 *
 * booking-service trusts this header completely. That is safe only because the
 * services have no published ports — the gateway is the only way in, so nothing
 * can reach them without passing through this filter first.
 */
@Component
public class UserIdHeaderFilter implements GlobalFilter, Ordered {

    public static final String USER_ID = "X-User-Id";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        return exchange.getPrincipal()
                .map(Principal::getName)
                .map(Optional::of)
                .defaultIfEmpty(Optional.empty())
                .flatMap(userId -> {
                    ServerHttpRequest request = exchange.getRequest().mutate()
                            .headers(h -> {
                                h.remove(USER_ID);                        // 1. never trust the client
                                userId.ifPresent(id -> h.set(USER_ID, id)); // 2. trust the token
                            })
                            .build();
                    return chain.filter(exchange.mutate().request(request).build());
                });
    }

    /** Early, so nothing downstream ever sees the client's version. */
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
