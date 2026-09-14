package com.middleberth.gateway.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.servers.Server;
import org.springframework.context.annotation.Configuration;

/**
 * The Swagger page at /swagger-ui.html, and how the gateway describes its own endpoints.
 *
 * The page lists one entry per part of the API (see springdoc in application.yml).
 * The gateway describes only signing up and logging in; each service describes
 * itself, and the page fetches those descriptions through the gateway.
 */
@Configuration
@OpenAPIDefinition(
        info = @Info(title = "MiddleBerth", version = "1",
                description = """
                        A tatkal train-booking backend. To try a booking:
                        1. POST /auth/signup below with any email and a password of 8+ characters.
                        2. Copy the token from the answer.
                        3. Pick "booking" in the list at the top right, press Authorize and paste it.
                        4. Check "search" for a train and date on sale, then POST /api/bookings and poll for the answer.
                        """),
        // "/" means the address this page was opened on, so "Try it out" goes to the
        // gateway, where tokens and rate limits apply.
        servers = @Server(url = "/"))
public class ApiDocsConfig {
}
