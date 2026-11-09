package com.ledgerline.config;

import java.time.Duration;
import java.util.Base64;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings for access tokens, bound from {@code ledgerline.jwt.*}.
 *
 * The secret is checked in {@link #signingKey()} rather than while binding. A failure during binding makes
 * Spring Boot's start-up report print the property's value, which here would be the secret itself.
 */
@ConfigurationProperties("ledgerline.jwt")
public record JwtProperties(
        String secret,
        @DefaultValue("ledgerline") String issuer,
        @DefaultValue("15m") Duration accessTokenTtl) {

    /** HS256 needs a key of at least 256 bits (RFC 7518, section 3.2). */
    static final int MIN_KEY_BYTES = 32;

    /** Decodes and checks the secret. The exception messages never contain any part of it. */
    public SecretKey signingKey() {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("ledgerline.jwt.secret (JWT_SECRET) is not set");
        }
        byte[] key;
        try {
            key = Base64.getDecoder().decode(secret.strip());
        } catch (IllegalArgumentException notBase64) {
            throw new IllegalStateException("ledgerline.jwt.secret (JWT_SECRET) is not valid Base64");
        }
        if (key.length < MIN_KEY_BYTES) {
            throw new IllegalStateException("ledgerline.jwt.secret (JWT_SECRET) must decode to at least "
                    + MIN_KEY_BYTES + " bytes");
        }
        return new SecretKeySpec(key, "HmacSHA256");
    }

    public void validate() {
        signingKey();
        if (issuer == null || issuer.isBlank()) {
            throw new IllegalStateException("ledgerline.jwt.issuer must not be blank");
        }
        if (accessTokenTtl == null || accessTokenTtl.isNegative() || accessTokenTtl.isZero()) {
            throw new IllegalStateException("ledgerline.jwt.access-token-ttl must be positive");
        }
    }

    @Override
    public String toString() {
        return "JwtProperties[secret=<hidden>, issuer=" + issuer + ", accessTokenTtl=" + accessTokenTtl + "]";
    }
}
