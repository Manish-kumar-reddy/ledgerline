package com.ledgerline.security;

import com.ledgerline.repository.UserRepository;
import com.ledgerline.validation.Emails;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/** Loads a user for login by (normalised) email. */
@Service
public class DatabaseUserDetailsService implements UserDetailsService {

    private final UserRepository users;

    public DatabaseUserDetailsService(UserRepository users) {
        this.users = users;
    }

    @Override
    public UserDetails loadUserByUsername(String email) {
        return users.findByEmail(Emails.normalise(email))
                .map(LoginAccount::new)
                .orElseThrow(() -> new UsernameNotFoundException("User not found"));
    }
}
