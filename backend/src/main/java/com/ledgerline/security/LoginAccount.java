package com.ledgerline.security;

import com.ledgerline.entity.User;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.CredentialsContainer;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/** What login needs to know about a user. The hash is erased by Spring Security once login has finished. */
public final class LoginAccount implements UserDetails, CredentialsContainer {

    private final UUID id;
    private final String email;
    private final boolean enabled;
    private String passwordHash;

    LoginAccount(User user) {
        this.id = user.getId();
        this.email = user.getEmail();
        this.enabled = user.isEnabled();
        this.passwordHash = user.getPasswordHash();
    }

    public UUID id() {
        return id;
    }

    @Override
    public String getUsername() {
        return email;
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    /** Login does not hand out authorities; requests get their role from the database (UserJwtAuthenticationConverter). */
    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of();
    }

    @Override
    public void eraseCredentials() {
        passwordHash = null;
    }

    @Override
    public String toString() {
        return "LoginAccount[id=" + id + ", enabled=" + enabled + "]";
    }
}
