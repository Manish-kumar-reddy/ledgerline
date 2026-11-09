package com.ledgerline.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Login rate limiting, bound from {@code ledgerline.login-rate-limit.*}. Each counter (per email, per client IP)
 * holds failed attempts plus attempts still being checked, in a fixed window that starts at the first counted
 * attempt. While either counter is at its maximum, further attempts get 429 until that window ends.
 * See LoginRateLimiter for the exact reservation policy.
 */
@ConfigurationProperties("ledgerline.login-rate-limit")
public record LoginRateLimitProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("5") int maxFailuresPerEmail,
        @DefaultValue("50") int maxFailuresPerIp,
        @DefaultValue("15m") Duration window,
        @DefaultValue("100000") int maxTrackedKeys) {

    public void validate() {
        if (maxFailuresPerEmail < 1 || maxFailuresPerIp < 1) {
            throw new IllegalStateException("ledgerline.login-rate-limit maximums must be at least 1");
        }
        if (maxTrackedKeys < 2) {
            // Each attempt needs an IP key and an email key; with room for fewer, every login would be refused.
            throw new IllegalStateException("ledgerline.login-rate-limit.max-tracked-keys must be at least 2");
        }
        if (window == null || window.isNegative() || window.isZero()) {
            throw new IllegalStateException("ledgerline.login-rate-limit.window must be positive");
        }
    }
}
