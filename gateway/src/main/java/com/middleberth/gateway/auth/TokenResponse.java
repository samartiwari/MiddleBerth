package com.middleberth.gateway.auth;

public record TokenResponse(String token, long expiresInSeconds) {
}
