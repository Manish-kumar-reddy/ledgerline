package com.ledgerline.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ledgerline.config.LoginRateLimitProperties;
import com.ledgerline.config.RegistrationRateLimitProperties;
import com.ledgerline.exception.TooManyAttemptsException;
import com.ledgerline.support.MutableClock;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** Unit tests (no database, no Spring) for the login and registration limiters' policies. */
class LoginRateLimiterTest {

    private static final String IP = "203.0.113.7";
    private static final String EMAIL = "asha@example.com";

    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-09T10:00:00Z"));

    private LoginRateLimiter limiter(int perEmail, int perIp) {
        return new LoginRateLimiter(new LoginRateLimitProperties(true, perEmail, perIp, Duration.ofMinutes(15), 1000), clock);
    }

    /** A failed login: reserve and keep. */
    private static void fail(LoginRateLimiter limiter, String ip, String email, int times) {
        for (int i = 0; i < times; i++) {
            limiter.reserve(ip, email);
        }
    }

    @Test
    void theAttemptAfterTheLimitIsRefusedWithRetryAfterAndNotCounted() {
        LoginRateLimiter limiter = limiter(3, 100);
        fail(limiter, IP, EMAIL, 3);
        clock.advance(Duration.ofMinutes(10));
        assertThatThrownBy(() -> limiter.reserve(IP, EMAIL))
                .isInstanceOf(TooManyAttemptsException.class)
                .hasMessage("Too many login attempts")
                .extracting(ex -> ((TooManyAttemptsException) ex).getRetryAfter()).isEqualTo(Duration.ofMinutes(5));
        assertThat(limiter.emailCount(EMAIL)).isEqualTo(3);
        assertThat(limiter.ipCount(IP)).isEqualTo(3);
    }

    @Test
    void theEmailLimitAppliesFromEveryIp() {
        LoginRateLimiter limiter = limiter(3, 100);
        fail(limiter, IP, EMAIL, 3);
        assertThatThrownBy(() -> limiter.reserve("198.51.100.1", EMAIL)).isInstanceOf(TooManyAttemptsException.class);
    }

    @Test
    void theIpLimitAppliesToEveryEmail() {
        LoginRateLimiter limiter = limiter(100, 4);
        fail(limiter, IP, "a@example.com", 2);
        fail(limiter, IP, "b@example.com", 2);
        assertThatThrownBy(() -> limiter.reserve(IP, "c@example.com")).isInstanceOf(TooManyAttemptsException.class);
        assertThatCode(() -> limiter.reserve("198.51.100.1", "c@example.com")).doesNotThrowAnyException();
    }

    @Test
    void aSuccessfulLoginGivesBackOnlyItsOwnAttempt() {
        LoginRateLimiter limiter = limiter(3, 100);
        fail(limiter, IP, EMAIL, 2);
        limiter.release(limiter.reserve(IP, EMAIL));       // a success: reserved, then released
        assertThat(limiter.emailCount(EMAIL)).as("earlier failures are not erased").isEqualTo(2);
        assertThat(limiter.ipCount(IP)).isEqualTo(2);
    }

    @Test
    void successfulLoginsAloneNeverReachTheLimit() {
        LoginRateLimiter limiter = limiter(3, 3);
        for (int i = 0; i < 20; i++) {
            limiter.release(limiter.reserve(IP, EMAIL));
        }
        assertThat(limiter.emailCount(EMAIL)).isZero();
    }

    @Test
    void countsResetWhenTheWindowEnds() {
        LoginRateLimiter limiter = limiter(3, 100);
        fail(limiter, IP, EMAIL, 3);
        clock.advance(Duration.ofMinutes(15));
        assertThatCode(() -> limiter.reserve(IP, EMAIL)).doesNotThrowAnyException();
        assertThat(limiter.emailCount(EMAIL)).isEqualTo(1);
    }

    @Test
    void aNewInstanceStartsEmptyBecauseNothingIsPersisted() {
        LoginRateLimiter before = limiter(3, 100);
        fail(before, IP, EMAIL, 3);
        assertThatCode(() -> limiter(3, 100).reserve(IP, EMAIL)).doesNotThrowAnyException();
    }

    @Test
    void disabledNeverBlocksAndTracksNothing() {
        LoginRateLimiter limiter = new LoginRateLimiter(new LoginRateLimitProperties(false, 1, 1, Duration.ofMinutes(15), 1000), clock);
        fail(limiter, IP, EMAIL, 10);
        assertThat(limiter.trackedKeys()).isEmpty();
    }

    @Test
    void storesNoEmailAddress() {
        LoginRateLimiter limiter = limiter(3, 100);
        fail(limiter, IP, EMAIL, 1);
        assertThat(limiter.trackedKeys()).hasSize(2)
                .allSatisfy(key -> assertThat(key).doesNotContain("asha").doesNotContain("example.com"))
                .anySatisfy(key -> assertThat(key).matches("email:[0-9a-f]{64}"))
                .contains("ip:" + IP);
    }

    @Test
    void registrationCountsEveryAttemptPerIp() {
        RegistrationRateLimiter registrations = new RegistrationRateLimiter(
                new RegistrationRateLimitProperties(true, 2, Duration.ofHours(1), 1000), clock);
        registrations.reserve(IP);
        registrations.reserve(IP);
        assertThatThrownBy(() -> registrations.reserve(IP))
                .isInstanceOf(TooManyAttemptsException.class).hasMessage("Too many registration attempts");
        assertThatCode(() -> registrations.reserve("198.51.100.1")).doesNotThrowAnyException();
        clock.advance(Duration.ofHours(1));
        assertThatCode(() -> registrations.reserve(IP)).as("reset after the window").doesNotThrowAnyException();
    }

    // ---- capacity policy --------------------------------------------------------------------------------------

    private LoginRateLimiter loginWithRoomFor(int keys, int perEmail, int perIp) {
        return new LoginRateLimiter(new LoginRateLimitProperties(true, perEmail, perIp, Duration.ofMinutes(15), keys), clock);
    }

    @Test
    void loginFullWithBothKeysNewIsRefusedAndCountsNothing() {
        LoginRateLimiter limiter = loginWithRoomFor(2, 5, 50);
        fail(limiter, IP, EMAIL, 1);                                   // fills the table: ip + email
        assertThatThrownBy(() -> limiter.reserve("198.51.100.1", "new@example.com"))
                .isInstanceOf(TooManyAttemptsException.class).hasMessage("Too many login attempts")
                .extracting(ex -> ((TooManyAttemptsException) ex).getRetryAfter()).isEqualTo(LoginRateLimiter.FULL_RETRY_AFTER);
        assertThat(limiter.trackedKeys()).hasSize(2).contains("ip:" + IP);
    }

    @Test
    void loginFullWithAnExistingIpAndANewEmailIsRefusedWithoutCountingTheIp() {
        LoginRateLimiter limiter = loginWithRoomFor(2, 5, 50);
        fail(limiter, IP, EMAIL, 1);
        assertThatThrownBy(() -> limiter.reserve(IP, "new@example.com")).isInstanceOf(TooManyAttemptsException.class);
        assertThat(limiter.ipCount(IP)).as("not partly counted").isEqualTo(1);
        assertThat(limiter.emailCount("new@example.com")).isZero();
        // Same email, new IP: also refused, and the email is not counted on its own.
        assertThatThrownBy(() -> limiter.reserve("198.51.100.1", EMAIL)).isInstanceOf(TooManyAttemptsException.class);
        assertThat(limiter.emailCount(EMAIL)).isEqualTo(1);
    }

    @Test
    void loginFullWithAKeyAtItsLimitReportsThatKeysRemainingTime() {
        LoginRateLimiter limiter = loginWithRoomFor(2, 2, 50);
        fail(limiter, IP, EMAIL, 2);                                   // email at its limit, table full
        clock.advance(Duration.ofMinutes(5));
        assertThatThrownBy(() -> limiter.reserve("198.51.100.1", EMAIL))
                .extracting(ex -> ((TooManyAttemptsException) ex).getRetryAfter()).isEqualTo(Duration.ofMinutes(10));
    }

    @Test
    void loginFullStillCountsAttemptsWhoseKeysAreAllTracked() {
        LoginRateLimiter limiter = loginWithRoomFor(2, 3, 50);
        fail(limiter, IP, EMAIL, 3);
        assertThat(limiter.emailCount(EMAIL)).isEqualTo(3);
        assertThatThrownBy(() -> limiter.reserve(IP, EMAIL)).isInstanceOf(TooManyAttemptsException.class);
    }

    @Test
    void loginNeedsRoomForAtLeastTwoKeys() {
        assertThatThrownBy(() -> loginWithRoomFor(1, 5, 50))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("max-tracked-keys");
    }

    @Test
    void registrationFullLetsANewIpThroughUntrackedButStillLimitsTrackedIps() {
        RegistrationRateLimiter registrations = new RegistrationRateLimiter(
                new RegistrationRateLimitProperties(true, 2, Duration.ofHours(1), 1), clock);
        registrations.reserve(IP);                                     // the table (1 key) is now full
        for (int i = 0; i < 5; i++) {
            AttemptLimiter.Reservation untracked = registrations.reserve("198.51.100.1");
            assertThat(untracked).isEqualTo(AttemptLimiter.Reservation.NONE);
            registrations.release(untracked);                         // as after an unexpected error: no effect
        }
        assertThat(registrations.trackedKeys()).containsExactly("ip:" + IP);
        assertThat(registrations.ipCount(IP)).isEqualTo(1);
        registrations.reserve(IP);
        assertThatThrownBy(() -> registrations.reserve(IP))
                .as("an IP that is tracked is still limited while full").isInstanceOf(TooManyAttemptsException.class);
        clock.advance(Duration.ofHours(1));
        registrations.reserve("198.51.100.1");
        assertThat(registrations.ipCount("198.51.100.1")).as("tracked once space frees").isEqualTo(1);
    }
}
