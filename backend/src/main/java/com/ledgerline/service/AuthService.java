package com.ledgerline.service;

import com.ledgerline.dto.LoginRequest;
import com.ledgerline.dto.RegisterRequest;
import com.ledgerline.dto.TokenResponse;
import com.ledgerline.dto.UserResponse;
import com.ledgerline.entity.User;
import com.ledgerline.exception.EmailAlreadyRegisteredException;
import com.ledgerline.exception.TooManyAttemptsException;
import com.ledgerline.repository.UserRepository;
import com.ledgerline.security.AttemptLimiter;
import com.ledgerline.security.JwtTokenService;
import com.ledgerline.security.LoginAccount;
import com.ledgerline.security.LoginAuthenticator;
import com.ledgerline.security.LoginRateLimiter;
import com.ledgerline.security.RegistrationRateLimiter;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.authentication.InternalAuthenticationServiceException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthService {

    /** Name of the UNIQUE constraint on users.email in V3. */
    static final String EMAIL_UNIQUE_CONSTRAINT = "uq_users_email";

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final LoginAuthenticator loginAuthenticator;
    private final JwtTokenService tokens;
    private final LoginRateLimiter loginLimiter;
    private final RegistrationRateLimiter registrationLimiter;

    public AuthService(UserRepository users, PasswordEncoder passwordEncoder, LoginAuthenticator loginAuthenticator,
            JwtTokenService tokens, LoginRateLimiter loginLimiter, RegistrationRateLimiter registrationLimiter) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.loginAuthenticator = loginAuthenticator;
        this.tokens = tokens;
        this.loginLimiter = loginLimiter;
        this.registrationLimiter = registrationLimiter;
    }

    /**
     * Creates an ACCOUNT_HOLDER. The role is not chosen here at all: the database assigns its default and the
     * runtime role may not insert any other value.
     *
     * The existence check gives a fast, clear answer in the common case; the UNIQUE constraint is what actually
     * guarantees uniqueness when two requests for the same email race each other.
     *
     * The per-IP registration limit is reserved first, before any database access or BCrypt hashing; over the
     * limit, {@link TooManyAttemptsException} (429) is thrown and nothing else happens. A created account and a
     * duplicate email both keep the reservation; only an unexpected failure releases it. Deliberately not
     * {@code @Transactional}: a refused request must not even borrow a database connection. The insert is
     * transactional on its own (saveAndFlush).
     */
    public UserResponse register(RegisterRequest request, String clientIp) {
        AttemptLimiter.Reservation reservation = registrationLimiter.reserve(clientIp);
        boolean keepReservation = false;
        try {
            if (users.existsByEmail(request.email())) {
                keepReservation = true;
                throw new EmailAlreadyRegisteredException();
            }
            User user = new User(request.fullName(), request.email(), passwordEncoder.encode(request.password()));
            try {
                users.saveAndFlush(user);
            } catch (DataIntegrityViolationException ex) {
                if (violates(ex, EMAIL_UNIQUE_CONSTRAINT)) {
                    keepReservation = true;
                    throw new EmailAlreadyRegisteredException();
                }
                throw ex;
            }
            keepReservation = true;
            return UserResponse.from(user);
        } finally {
            if (!keepReservation) {
                registrationLimiter.release(reservation);
            }
        }
    }

    /**
     * The attempt is reserved against the IP and email limits atomically, before the password is checked; over
     * either limit, {@link TooManyAttemptsException} (429) is thrown and nothing is counted. Then:
     * a failed login ({@link AuthenticationException}, always the same 401) keeps the reservation;
     * a successful login, or an unexpected error, releases exactly this attempt's reservation.
     * Spring wraps errors from the user lookup (e.g. the database is down) in
     * {@link InternalAuthenticationServiceException}, which is an AuthenticationException but not a wrong
     * password: it is unwrapped, so it is released and becomes a 500, never the 401 for invalid credentials.
     * The limiter is given only the IP and the normalised email, never the password.
     */
    public TokenResponse login(LoginRequest request, String clientIp) {
        AttemptLimiter.Reservation reservation = loginLimiter.reserve(clientIp, request.email());
        boolean keepReservation = false;
        LoginAccount account;
        try {
            account = loginAuthenticator.authenticate(request.email(), request.password());
        } catch (InternalAuthenticationServiceException internal) {
            throw internal.getCause() instanceof RuntimeException cause ? cause
                    : new IllegalStateException("Login could not be completed", internal);
        } catch (AuthenticationException failed) {
            keepReservation = true;
            throw failed;
        } finally {
            if (!keepReservation) {
                loginLimiter.release(reservation);
            }
        }
        JwtTokenService.IssuedToken token = tokens.issue(account.id());
        return TokenResponse.bearer(token.value(), token.expiresInSeconds());
    }

    private static boolean violates(Throwable ex, String constraint) {
        for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation
                    && constraint.equalsIgnoreCase(violation.getConstraintName())) {
                return true;
            }
        }
        return false;
    }
}
