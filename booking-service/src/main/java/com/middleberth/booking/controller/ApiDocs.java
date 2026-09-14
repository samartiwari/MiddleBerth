package com.middleberth.booking.controller;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.annotations.servers.Server;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * How this service describes itself to the Swagger page on the gateway.
 */
@Configuration
@OpenAPIDefinition(
        info = @Info(title = "Bookings and passengers", version = "1",
                description = "Needs a token: sign up or log in under gateway-auth, copy the token, "
                        + "then press Authorize here."),
        // "/" means the address the docs page was opened on, which is the gateway.
        // Without it springdoc writes this service's own address, which exists only
        // inside Docker, and every "Try it out" would fail.
        servers = @Server(url = "/"),
        security = @SecurityRequirement(name = ApiDocs.BEARER))
@SecurityScheme(name = ApiDocs.BEARER, type = SecuritySchemeType.HTTP, scheme = "bearer", bearerFormat = "JWT")
public class ApiDocs {

    static final String BEARER = "bearer-jwt";

    /**
     * Who is calling comes from the token, and the gateway throws away any X-User-Id a
     * client sends. Listing it as a parameter would invite people to fill it in.
     * One rule here rather than an annotation on every method, so a new endpoint
     * cannot forget it.
     */
    @Bean
    OperationCustomizer hideTheUserIdHeader() {
        return (operation, handler) -> {
            if (operation.getParameters() != null) {
                operation.getParameters().removeIf(p -> "header".equals(p.getIn())
                        && BookingController.USER_ID.equalsIgnoreCase(p.getName()));
            }
            return operation;
        };
    }
}
