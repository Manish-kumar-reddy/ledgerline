package com.ledgerline.security;

import com.ledgerline.config.RegistrationRateLimitProperties;
import com.ledgerline.exception.TooManyAttemptsException;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Registration limit per client IP. Each registration attempt that passes request validation is reserved
 * atomically before the duplicate-email check, BCrypt hashing and the insert. Successful registrations and
 * duplicate-email (409) attempts stay counted, because creating accounts and hashing passwords is the cost being
 * limited. Only attempts that fail unexpectedly (e.g. a database outage) are released.
 *
 * <p>Capacity (fail open, whole attempt untracked): if the tracking table is full and this IP is not already
 * tracked, the attempt is allowed and not counted. IPs already tracked are still enforced. Refusing instead would
 * give anyone who fills the table (cheap: duplicate-email attempts count and need no BCrypt) an outage of all
 * registrations, while an attacker able to fill it controls so many addresses that a per-IP limit no longer
 * restrains them anyway. The limiter logs a warning when this starts.
 */
@Component
public class RegistrationRateLimiter {

    static final String TITLE = "Too many registration attempts";

    private final RegistrationRateLimitProperties properties;
    private final AttemptLimiter limiter;

    public RegistrationRateLimiter(RegistrationRateLimitProperties properties, Clock clock) {
        properties.validate();
        this.properties = properties;
        this.limiter = new AttemptLimiter("Registration", clock, properties.window(), properties.maxTrackedKeys());
    }

    public AttemptLimiter.Reservation reserve(String clientIp) {
        if (!properties.enabled()) {
            return AttemptLimiter.Reservation.NONE;
        }
        List<AttemptLimiter.Limit> limits =
                List.of(new AttemptLimiter.Limit(LoginRateLimiter.ipKey(clientIp), properties.maxAttemptsPerIp()));
        return switch (limiter.tryReserve(limits, AttemptLimiter.OnFull.ALLOW_UNTRACKED)) {
            case AttemptLimiter.Reserved reserved -> reserved.reservation();
            case AttemptLimiter.Blocked blocked -> throw new TooManyAttemptsException(TITLE, blocked.retryAfter());
            case AttemptLimiter.Full impossible -> throw new IllegalStateException("ALLOW_UNTRACKED never returns Full");
        };
    }

    public void release(AttemptLimiter.Reservation reservation) {
        limiter.release(reservation);
    }

    int ipCount(String clientIp) {
        return limiter.count(LoginRateLimiter.ipKey(clientIp));
    }

    Set<String> trackedKeys() {
        return limiter.trackedKeys();
    }
}
