package com.ledgerline.security;

import com.ledgerline.repository.UserRepository;
import java.util.UUID;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;

/**
 * Turns a token whose signature, issuer and expiry are already verified into an authenticated user.
 *
 * The token subject is the user's id. The user is looked up in PostgreSQL on every request, so:
 * a user who no longer exists or has been disabled is refused at once, even with an unexpired token; and the
 * role always comes from the database, never from the token.
 */
public final class UserJwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private final UserRepository users;

    public UserJwtAuthenticationConverter(UserRepository users) {
        this.users = users;
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        UUID userId = parseUserId(jwt.getSubject());
        return users.findById(userId)
                .filter(user -> user.isEnabled())
                .map(user -> new UserAuthentication(AuthenticatedUser.from(user), jwt))
                // Same message for "no such user" and "disabled", so a token holder learns nothing more.
                .orElseThrow(() -> new InvalidBearerTokenException("Invalid token"));
    }

    private static UUID parseUserId(String subject) {
        try {
            return UUID.fromString(subject);
        } catch (IllegalArgumentException | NullPointerException notAUuid) {
            throw new InvalidBearerTokenException("Invalid token");
        }
    }
}
