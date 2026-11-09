package com.ledgerline.dto;

import com.ledgerline.entity.Role;
import com.ledgerline.entity.User;
import com.ledgerline.security.AuthenticatedUser;
import java.time.Instant;
import java.util.UUID;

/** Public view of a user. It has no password or hash field, so neither can ever be serialised. */
public record UserResponse(UUID id, String fullName, String email, Role role, Instant createdAt) {

    public static UserResponse from(User user) {
        return new UserResponse(user.getId(), user.getFullName(), user.getEmail(), user.getRole(), user.getCreatedAt());
    }

    public static UserResponse from(AuthenticatedUser user) {
        return new UserResponse(user.id(), user.fullName(), user.email(), user.role(), user.createdAt());
    }
}
