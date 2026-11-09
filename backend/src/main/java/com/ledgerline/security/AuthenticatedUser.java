package com.ledgerline.security;

import com.ledgerline.entity.Role;
import com.ledgerline.entity.User;
import java.time.Instant;
import java.util.UUID;

/** The signed-in user for the current request, loaded fresh from PostgreSQL (never from token claims). */
public record AuthenticatedUser(UUID id, String fullName, String email, Role role, Instant createdAt) {

    public static AuthenticatedUser from(User user) {
        return new AuthenticatedUser(user.getId(), user.getFullName(), user.getEmail(), user.getRole(), user.getCreatedAt());
    }
}
