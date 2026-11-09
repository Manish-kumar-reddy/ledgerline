package com.ledgerline.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * The user making the current request, as loaded from PostgreSQL by {@link UserJwtAuthenticationConverter}.
 *
 * Services take the caller from here rather than from a method parameter, so no controller (or future caller) can
 * pass in someone else's identity. This is the only source of account ownership.
 */
public final class CurrentUser {

    private CurrentUser() {
    }

    public static AuthenticatedUser require() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof UserAuthentication user && user.isAuthenticated()) {
            return user.getPrincipal();
        }
        // Unreachable through the API: URL rules and @PreAuthorize run first. Failing loudly is a programming error.
        throw new IllegalStateException("No authenticated LedgerLine user in the security context");
    }
}
