package com.ledgerline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ledgerline.config.LoginRateLimitProperties;
import com.ledgerline.config.RegistrationRateLimitProperties;
import com.ledgerline.dto.LoginRequest;
import com.ledgerline.dto.RegisterRequest;
import com.ledgerline.exception.EmailAlreadyRegisteredException;
import com.ledgerline.exception.TooManyAttemptsException;
import com.ledgerline.repository.UserRepository;
import com.ledgerline.security.DatabaseUserDetailsService;
import com.ledgerline.security.JwtTokenService;
import com.ledgerline.security.LoginAccount;
import com.ledgerline.security.LoginAuthenticator;
import com.ledgerline.security.LoginRateLimiter;
import com.ledgerline.security.RegistrationRateLimiter;
import com.ledgerline.support.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * AuthService with real rate limiters and mocked collaborators (no database, no Spring). The authenticator is
 * made to wait on a latch, so attempts are provably in flight at the same time: this is the H1 race, made
 * deterministic.
 */
class AuthServiceRateLimitTest {

    private static final String EMAIL = "victim@example.com";

    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-09T10:00:00Z"));
    private final UserRepository users = mock(UserRepository.class);
    private final PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
    private final LoginAuthenticator authenticator = mock(LoginAuthenticator.class);
    private final JwtTokenService tokens = mock(JwtTokenService.class);

    private AuthService service(int loginPerEmail, int loginPerIp, int registrationsPerIp) {
        return new AuthService(users, passwordEncoder, authenticator, tokens,
                new LoginRateLimiter(new LoginRateLimitProperties(true, loginPerEmail, loginPerIp, Duration.ofMinutes(15), 1000), clock),
                new RegistrationRateLimiter(new RegistrationRateLimitProperties(true, registrationsPerIp, Duration.ofHours(1), 1000), clock));
    }

    @Test
    void parallelAttemptsCannotAllReachThePasswordCheck() throws Exception {
        AuthService service = service(5, 1000, 1000);
        int attempts = 40;
        CountDownLatch inPasswordCheck = new CountDownLatch(5);
        CountDownLatch finishPasswordCheck = new CountDownLatch(1);
        AtomicInteger passwordChecks = new AtomicInteger();
        CountDownLatch refused = new CountDownLatch(attempts - 5);
        when(authenticator.authenticate(anyString(), anyString())).thenAnswer(call -> {
            passwordChecks.incrementAndGet();
            inPasswordCheck.countDown();
            finishPasswordCheck.await(10, TimeUnit.SECONDS);
            throw new BadCredentialsException("Bad credentials");
        });

        CountDownLatch go = new CountDownLatch(1);
        List<Future<String>> results = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(attempts)) {
            for (int i = 0; i < attempts; i++) {
                int guess = i;
                results.add(pool.submit(() -> {
                    go.await();
                    try {
                        service.login(new LoginRequest(EMAIL, "guess " + guess), "203.0.113.1");
                        return "200";
                    } catch (TooManyAttemptsException tooMany) {
                        refused.countDown();
                        return "429";
                    } catch (BadCredentialsException wrong) {
                        return "401";
                    }
                }));
            }
            go.countDown();
            // 5 attempts are now held inside the password check. Every other attempt must be refused while they
            // are still there: if the limiter only counted after the check (the H1 bug), 'refused' would never
            // reach zero, more than 5 would enter the check, and this await would time out.
            assertThat(inPasswordCheck.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(refused.await(10, TimeUnit.SECONDS)).as("35 refused while 5 are in flight").isTrue();
            assertThat(passwordChecks.get()).as("no 6th attempt entered the check").isEqualTo(5);
            finishPasswordCheck.countDown();
        }
        assertThat(passwordChecks.get()).isEqualTo(5);
        assertThat(results.stream().map(Future::resultNow).filter("401"::equals)).hasSize(5);
    }

    @Test
    void aSuccessInFlightDoesNotEraseAConcurrentFailure() throws Exception {
        AuthService service = service(2, 1000, 1000);
        LoginAccount account = mock(LoginAccount.class);
        when(tokens.issue(any())).thenReturn(new JwtTokenService.IssuedToken("t", 900));
        CountDownLatch bothInFlight = new CountDownLatch(2);
        CountDownLatch finishSuccess = new CountDownLatch(1);
        CountDownLatch finishFailure = new CountDownLatch(1);
        when(authenticator.authenticate(anyString(), anyString())).thenAnswer(call -> {
            bothInFlight.countDown();
            if ("right".equals(call.getArgument(1))) {
                finishSuccess.await(10, TimeUnit.SECONDS);
                return account;
            }
            finishFailure.await(10, TimeUnit.SECONDS);
            throw new BadCredentialsException("Bad credentials");
        });
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<?> success = pool.submit(() -> service.login(new LoginRequest(EMAIL, "right"), "203.0.113.2"));
            Future<?> failure = pool.submit(() -> {
                try {
                    service.login(new LoginRequest(EMAIL, "wrong"), "203.0.113.3");
                } catch (BadCredentialsException expected) {
                    // counted
                }
            });
            assertThat(bothInFlight.await(10, TimeUnit.SECONDS)).isTrue();
            finishSuccess.countDown();          // the success completes first, while the failure is still in flight
            success.get();
            finishFailure.countDown();
            failure.get();
        }
        // Limit 2: the success gave back only its own slot, so exactly one (the failure) is still counted.
        // One more failure is allowed; the attempt after that is refused.
        // doThrow(..).when(mock): re-stubbing with when(mock.authenticate(..)) would call the previous answer.
        doThrow(new BadCredentialsException("Bad credentials")).when(authenticator).authenticate(anyString(), anyString());
        assertThatThrownBy(() -> service.login(new LoginRequest(EMAIL, "wrong 2"), "203.0.113.4")).isInstanceOf(BadCredentialsException.class);
        assertThatThrownBy(() -> service.login(new LoginRequest(EMAIL, "wrong 3"), "203.0.113.5")).isInstanceOf(TooManyAttemptsException.class);
    }

    @Test
    void anUnexpectedErrorGivesTheAttemptBack() {
        AuthService service = service(1, 1000, 1000);
        when(authenticator.authenticate(anyString(), anyString())).thenThrow(new DataAccessResourceFailureException("database down"));
        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> service.login(new LoginRequest(EMAIL, "x"), "203.0.113.6"))
                    .isInstanceOf(DataAccessResourceFailureException.class);
        }
    }

    @Test
    void registrationOverTheLimitDoesNoDatabaseWorkAndNoHashing() {
        AuthService service = service(5, 50, 2);
        when(users.existsByEmail(anyString())).thenReturn(true);   // both allowed attempts are duplicates (409)
        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> service.register(new RegisterRequest("A", "a@example.com", "a long password"), "203.0.113.9"))
                    .isInstanceOf(EmailAlreadyRegisteredException.class);
        }
        org.mockito.Mockito.clearInvocations(users, passwordEncoder);

        assertThatThrownBy(() -> service.register(new RegisterRequest("A", "b@example.com", "a long password"), "203.0.113.9"))
                .isInstanceOf(TooManyAttemptsException.class).hasMessage("Too many registration attempts");
        verify(users, never()).existsByEmail(anyString());
        verify(users, never()).saveAndFlush(any());
        verify(passwordEncoder, never()).encode(any());
    }

    @Test
    void parallelRegistrationsFromOneIpGetExactlyTheLimit() throws Exception {
        AuthService service = service(5, 50, 3);
        CountDownLatch inside = new CountDownLatch(3);
        CountDownLatch finish = new CountDownLatch(1);
        when(users.existsByEmail(anyString())).thenAnswer(call -> {
            inside.countDown();
            finish.await(10, TimeUnit.SECONDS);
            return true;
        });
        CountDownLatch go = new CountDownLatch(1);
        List<Future<String>> results = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(30)) {
            for (int i = 0; i < 30; i++) {
                int n = i;
                results.add(pool.submit(() -> {
                    go.await();
                    try {
                        service.register(new RegisterRequest("A", "r" + n + "@example.com", "a long password"), "203.0.113.10");
                        return "201";
                    } catch (TooManyAttemptsException tooMany) {
                        return "429";
                    } catch (EmailAlreadyRegisteredException duplicate) {
                        return "409";
                    }
                }));
            }
            go.countDown();
            assertThat(inside.await(10, TimeUnit.SECONDS)).isTrue();
            finish.countDown();
        }
        assertThat(results.stream().map(Future::resultNow).filter("429"::equals)).hasSize(27);
        assertThat(results.stream().map(Future::resultNow).filter("409"::equals)).hasSize(3);
    }

    @Test
    void aFullLoginTableRefusesNewKeysBeforeThePasswordCheck() {
        AuthService service = new AuthService(users, passwordEncoder, authenticator, tokens,
                new LoginRateLimiter(new LoginRateLimitProperties(true, 5, 50, Duration.ofMinutes(15), 2), clock),
                new RegistrationRateLimiter(new RegistrationRateLimitProperties(true, 10, Duration.ofHours(1), 1000), clock));
        when(authenticator.authenticate(anyString(), anyString())).thenThrow(new BadCredentialsException("Bad credentials"));
        assertThatThrownBy(() -> service.login(new LoginRequest(EMAIL, "wrong"), "203.0.113.20"))
                .isInstanceOf(BadCredentialsException.class);           // fills the table (ip + email)
        org.mockito.Mockito.clearInvocations(authenticator);

        assertThatThrownBy(() -> service.login(new LoginRequest("other@example.com", "x"), "203.0.113.21"))
                .isInstanceOf(TooManyAttemptsException.class);
        verify(authenticator, never()).authenticate(anyString(), anyString());
    }

    @Test
    void aDatabaseOutageThroughTheRealAuthenticatorIsNeitherA401NorCounted() {
        // The real LoginAuthenticator: Spring wraps the repository's exception in
        // InternalAuthenticationServiceException (an AuthenticationException). It must not count as a wrong password.
        when(users.findByEmail(anyString())).thenThrow(new DataAccessResourceFailureException("database down"));
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4);
        LoginRateLimiter loginLimiter =
                new LoginRateLimiter(new LoginRateLimitProperties(true, 5, 50, Duration.ofMinutes(15), 1000), clock);
        AuthService service = new AuthService(users, encoder, new LoginAuthenticator(new DatabaseUserDetailsService(users), encoder),
                tokens, loginLimiter,
                new RegistrationRateLimiter(new RegistrationRateLimitProperties(true, 10, Duration.ofHours(1), 1000), clock));
        for (int i = 0; i < 12; i++) {                                // more than the limit of 5
            assertThatThrownBy(() -> service.login(new LoginRequest(EMAIL, "whatever"), "203.0.113.30"))
                    .isExactlyInstanceOf(DataAccessResourceFailureException.class);
        }
        // Nothing was consumed: once the database is back, a wrong password is an ordinary failure, not a 429.
        org.mockito.Mockito.reset(users);
        assertThatThrownBy(() -> service.login(new LoginRequest(EMAIL, "whatever"), "203.0.113.30"))
                .isInstanceOf(BadCredentialsException.class);
    }
}
