package com.ledgerline.security;

import org.springframework.security.authentication.AccountStatusUserDetailsChecker;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Checks an email and password. Every failure is an {@link AuthenticationException}, which the API turns into
 * the same generic 401 response.
 *
 * Kept as a private AuthenticationManager (not a bean) so it cannot interfere with the bearer-token
 * authentication that Spring Security configures for every other request.
 */
@Component
public class LoginAuthenticator {

    private final AuthenticationManager manager;

    public LoginAuthenticator(DatabaseUserDetailsService userDetailsService, PasswordEncoder passwordEncoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        // By default a disabled account is rejected BEFORE the password is checked, which skips the slow BCrypt
        // comparison and makes "disabled" measurably faster than "wrong password". Checking account status
        // only after the password keeps every outcome on the same slow path.
        // Unknown emails are covered by DaoAuthenticationProvider itself: it runs a dummy BCrypt comparison.
        provider.setPreAuthenticationChecks(account -> { });
        provider.setPostAuthenticationChecks(new AccountStatusUserDetailsChecker());
        this.manager = new ProviderManager(provider);
    }

    /** Returns the account on success; throws {@link AuthenticationException} on any failure. */
    public LoginAccount authenticate(String email, String password) {
        return (LoginAccount) manager
                .authenticate(UsernamePasswordAuthenticationToken.unauthenticated(email, password))
                .getPrincipal();
    }
}
