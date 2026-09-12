package com.middleberth.gateway.dto;

public record TokenResponse(String token, long expiresInSeconds) {
}
