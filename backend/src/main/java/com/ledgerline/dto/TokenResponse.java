package com.ledgerline.dto;

/** Login result. {@code expiresIn} is in seconds (RFC 6749 style). */
public record TokenResponse(String accessToken, String tokenType, long expiresIn) {

    public static TokenResponse bearer(String accessToken, long expiresInSeconds) {
        return new TokenResponse(accessToken, "Bearer", expiresInSeconds);
    }

    @Override
    public String toString() {
        return "TokenResponse[accessToken=<hidden>, tokenType=" + tokenType + ", expiresIn=" + expiresIn + "]";
    }
}
