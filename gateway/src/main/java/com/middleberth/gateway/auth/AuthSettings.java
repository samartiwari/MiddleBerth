package com.middleberth.gateway.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * demoTokens hands out a token for any user id you name, with no password. It is
 * how the load test creates two thousand identities without two thousand BCrypt
 * hashes, and it is OFF unless something turns it on. Never on in production.
 */
@ConfigurationProperties(prefix = "middleberth.auth")
public record AuthSettings(boolean demoTokens,
                           int maxLoginFailures,
                           Duration lockout,
                           int maxSignupsPerIp,
                           Duration signupWindow) {
}
