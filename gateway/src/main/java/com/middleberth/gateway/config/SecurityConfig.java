package com.middleberth.gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;
import org.springframework.security.web.server.savedrequest.NoOpServerRequestCache;

/**
 * Who may call what.
 *
 * Anything not listed is denied, so a new route added later is closed until
 * someone decides otherwise — safer than the other way round.
 */
@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    @Bean
    SecurityWebFilterChain security(ServerHttpSecurity http) {
        return http
                // A token API with no cookies and no browser session — CSRF protects
                // cookie-based sessions, which this does not have.
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                // And no sessions at all. Spring Security's defaults are for a website:
                // keep who is logged in inside a session, and remember the page you
                // wanted so logging in can send you back to it. Here every request
                // brings its own token and there is no login page, so all they did was
                // make a session per request and throw it away — and making one hops
                // to another thread pool and back. In a 4,000-person rush the gateway's
                // request threads were caught queueing at that handover.
                .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
                .requestCache(cache -> cache.requestCache(NoOpServerRequestCache.getInstance()))
                .authorizeExchange(ex -> ex
                        .pathMatchers("/auth/**").permitAll()
                        .pathMatchers("/api/trains/**").permitAll()
                        .pathMatchers("/webhooks/razorpay").permitAll()   // trusted by its signature, not a token
                        .pathMatchers("/actuator/health/**").permitAll()
                        .pathMatchers("/api/bookings/**").authenticated()
                        .pathMatchers("/api/passengers/**").authenticated()
                        .anyExchange().denyAll())
                .oauth2ResourceServer(o -> o.jwt(Customizer.withDefaults()))
                .build();
    }
}
