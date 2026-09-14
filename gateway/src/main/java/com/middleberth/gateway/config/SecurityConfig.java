package com.middleberth.gateway.config;

import java.time.Duration;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;
import org.springframework.security.web.server.savedrequest.NoOpServerRequestCache;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

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
                // Before the rules below, so the browser's "may I?" question, which
                // carries no token, is answered here instead of refused with a 401.
                .cors(Customizer.withDefaults())
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
                        // The Swagger page and the API descriptions it reads. Public because
                        // they describe the API rather than call it, and GET only. "Try it out"
                        // sends real requests to the real paths, which still meet every rule here.
                        .pathMatchers(HttpMethod.GET, "/swagger-ui.html", "/swagger-ui/**",
                                "/v3/api-docs", "/v3/api-docs/**").permitAll()
                        .pathMatchers("/api/bookings/**").authenticated()
                        .pathMatchers("/api/passengers/**").authenticated()
                        .anyExchange().denyAll())
                .oauth2ResourceServer(o -> o.jwt(Customizer.withDefaults()))
                .build();
    }

    /**
     * Which web pages on other addresses may call this API from a browser.
     *
     * A browser will not let a page read an answer from another address unless
     * that address names the page. Only the listed pages are named; every other
     * site is refused, so it cannot use a visitor's browser to call this. Empty
     * means none, which is right for anything with no frontend of its own.
     *
     * No cookies are allowed through: the token travels in a header the page
     * sets itself, so another site has nothing to borrow.
     */
    @Bean
    CorsConfigurationSource corsConfigurationSource(
            @Value("${middleberth.cors.allowed-origins:}") List<String> allowedOrigins) {
        CorsConfiguration cors = new CorsConfiguration();
        cors.setAllowedOrigins(allowedOrigins.stream().map(String::trim).filter(o -> !o.isEmpty()).toList());
        // DELETE removes a saved passenger.
        cors.setAllowedMethods(List.of("GET", "POST", "DELETE"));
        cors.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        // The browser may reuse the permission for an hour instead of asking before every call.
        cors.setMaxAge(Duration.ofHours(1));

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", cors);
        return source;
    }
}
