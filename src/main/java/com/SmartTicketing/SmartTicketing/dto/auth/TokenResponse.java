package com.SmartTicketing.SmartTicketing.dto.auth;

public record TokenResponse(String accessToken, String tokenType, long expiresIn) {
}
