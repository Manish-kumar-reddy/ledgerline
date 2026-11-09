package com.ledgerline.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Registration rate limiting per client IP, bound from {@code ledgerline.registration-rate-limit.*}. */
@ConfigurationProperties("ledgerline.registration-rate-limit")
public record RegistrationRateLimitProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("10") int maxAttemptsPerIp,
        @DefaultValue("1h") Duration window,
        @DefaultValue("100000") int maxTrackedKeys) {

    public void validate() {
        if (maxAttemptsPerIp < 1 || maxTrackedKeys < 1) {
            throw new IllegalStateException("ledgerline.registration-rate-limit maximums must be at least 1");
        }
        if (window == null || window.isNegative() || window.isZero()) {
            throw new IllegalStateException("ledgerline.registration-rate-limit.window must be positive");
        }
    }
}
