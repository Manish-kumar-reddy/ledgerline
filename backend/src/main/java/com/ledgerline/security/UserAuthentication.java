package com.ledgerline.security;

import java.util.List;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

/** An authenticated request: the user and role come from the database; the token only proved who is asking. */
public final class UserAuthentication extends AbstractAuthenticationToken {

    private final AuthenticatedUser user;
    private final transient Jwt token;

    public UserAuthentication(AuthenticatedUser user, Jwt token) {
        super(List.of(new SimpleGrantedAuthority("ROLE_" + user.role().name())));
        this.user = user;
        this.token = token;
        setAuthenticated(true);
    }

    @Override
    public AuthenticatedUser getPrincipal() {
        return user;
    }

    @Override
    public Jwt getCredentials() {
        return token;
    }

    @Override
    public String getName() {
        return user.id().toString();
    }
}
