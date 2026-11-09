package com.ledgerline.security;

import com.ledgerline.config.LoginRateLimitProperties;
import com.ledgerline.exception.TooManyAttemptsException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * Login limits per client IP and per email (policy described in README.md, "Login rate limiting").
 *
 * <p>Every login attempt is reserved against both counters in one atomic step <em>before</em> the password is
 * checked, so concurrent requests cannot exceed a limit. The caller then:
 * <ul>
 *   <li>keeps the reservation if the login failed (it stays counted for the rest of the window);</li>
 *   <li>releases it if the login succeeded or failed for an unexpected reason, which removes only that
 *       attempt's own count and never clears other attempts' counts.</li>
 * </ul>
 * So each counter holds failed attempts plus attempts still being checked. A successful login does not erase
 * earlier failures; they expire with the window.
 *
 * <p>Capacity (fail closed): if the tracking table is full and this attempt needs a new email or IP key, the
 * attempt is refused with 429 before any password check, and nothing is counted. It is never partly tracked:
 * letting it through untracked would switch off the per-email limit, the main defence against attackers who
 * rotate IP addresses. Filling the table needs at least maxTrackedKeys / 2 failed password checks within one
 * window, so it only happens under an attack that is already loading the CPU heavily.
 *
 * <p>Stored per key: a window start and a count. Emails are never stored in plain text: the key is an
 * HMAC-SHA256 of the normalised email under a random key created at start-up. Passwords never reach this class.
 */
@Component
public class LoginRateLimiter {

    static final String TITLE = "Too many login attempts";

    /** Retry-After when refused because the table is full (no single key's window applies). */
    static final Duration FULL_RETRY_AFTER = Duration.ofMinutes(1);

    private final LoginRateLimitProperties properties;
    private final AttemptLimiter limiter;
    private final SecretKeySpec emailKey;

    public LoginRateLimiter(LoginRateLimitProperties properties, Clock clock) {
        properties.validate();
        this.properties = properties;
        this.limiter = new AttemptLimiter("Login", clock, properties.window(), properties.maxTrackedKeys());
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        this.emailKey = new SecretKeySpec(key, "HmacSHA256");
    }

    /**
     * Counts this attempt against both the IP and the email, or throws {@link TooManyAttemptsException} (counting
     * nothing) if either is at its limit or the table is full and a new key would be needed.
     */
    public AttemptLimiter.Reservation reserve(String clientIp, String normalisedEmail) {
        if (!properties.enabled()) {
            return AttemptLimiter.Reservation.NONE;
        }
        List<AttemptLimiter.Limit> limits = List.of(
                new AttemptLimiter.Limit(ipKey(clientIp), properties.maxFailuresPerIp()),
                new AttemptLimiter.Limit(emailKey(normalisedEmail), properties.maxFailuresPerEmail()));
        return switch (limiter.tryReserve(limits, AttemptLimiter.OnFull.REJECT)) {
            case AttemptLimiter.Reserved reserved -> reserved.reservation();
            case AttemptLimiter.Blocked blocked -> throw new TooManyAttemptsException(TITLE, blocked.retryAfter());
            case AttemptLimiter.Full full -> throw new TooManyAttemptsException(TITLE, FULL_RETRY_AFTER);
        };
    }

    /** Removes exactly this attempt's count (after a successful login or an unexpected error). */
    public void release(AttemptLimiter.Reservation reservation) {
        limiter.release(reservation);
    }

    /** For tests: current counts. */
    int ipCount(String clientIp) {
        return limiter.count(ipKey(clientIp));
    }

    int emailCount(String normalisedEmail) {
        return limiter.count(emailKey(normalisedEmail));
    }

    Set<String> trackedKeys() {
        return limiter.trackedKeys();
    }

    static String ipKey(String clientIp) {
        return "ip:" + (clientIp == null ? "unknown" : clientIp);
    }

    private String emailKey(String normalisedEmail) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(emailKey);
            byte[] digest = mac.doFinal(String.valueOf(normalisedEmail).getBytes(StandardCharsets.UTF_8));
            return "email:" + HexFormat.of().formatHex(digest);
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException("HmacSHA256 is not available", impossible);
        }
    }
}
