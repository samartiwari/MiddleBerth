package com.middleberth.gateway.config;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.cloud.gateway.support.ConfigurationService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.List;

@Configuration
public class RateLimitConfig {

    /** Spring Cloud Gateway's own token bucket script, inside its jar. */
    static final String SCRIPT = "META-INF/scripts/request_rate_limiter.lua";

    /**
     * Rate limit buckets are keyed by the user id inside the token — not by IP.
     *
     * So everyone behind one college wifi or one Jio NAT gets their own bucket,
     * and one person hammering Book cannot use up anyone else's.
     */
    @Bean
    KeyResolver userKeyResolver() {
        return exchange -> exchange.getPrincipal().map(Principal::getName);
    }

    /**
     * The same rate limiter Spring would build, except its Lua script is a string
     * instead of a file.
     *
     * Spring loads the script as a file. Before every call to Redis, Spring Data
     * Redis takes a lock and asks whether that file has changed since last time —
     * and because the file is inside a jar, finding out means throwing an exception
     * and opening the jar entry, on every booking and every poll, while every other
     * request waits for the lock. Thread dumps in the middle of a 4,000-person rush
     * caught up to 15 of the gateway's 16 threads parked on it.
     *
     * The file can never change, so the check is pointless. A script made from a
     * string answers "not changed" straight away. Same script, same Redis keys, same
     * limits: it is read from Spring's jar once, at startup, so an upgrade cannot
     * leave an old copy behind. Spring's own limiter steps aside when this one exists.
     */
    @Bean
    RedisRateLimiter redisRateLimiter(ReactiveStringRedisTemplate redis, ConfigurationService config) throws IOException {
        String lua = new ClassPathResource(SCRIPT).getContentAsString(StandardCharsets.UTF_8);
        @SuppressWarnings({"unchecked", "rawtypes"})
        RedisScript<List<Long>> script = (RedisScript) RedisScript.of(lua, List.class);
        return new RedisRateLimiter(redis, script, config);
    }
}
