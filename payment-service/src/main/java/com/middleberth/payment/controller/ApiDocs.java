package com.middleberth.payment.controller;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.servers.Server;
import org.springframework.context.annotation.Configuration;

/**
 * How this service describes itself to the Swagger page on the gateway.
 */
@Configuration
@OpenAPIDefinition(
        info = @Info(title = "Razorpay webhook", version = "1",
                description = "Called by Razorpay, not by people. Without a valid signature it is refused."),
        // "/" means the address the docs page was opened on, which is the gateway.
        // Without it springdoc writes this service's own address, which exists only
        // inside Docker, and every "Try it out" would fail.
        servers = @Server(url = "/"))
public class ApiDocs {
}
