package com.ledgerline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.ledgerline.dto.CreateAccountRequest;
import com.ledgerline.entity.Account;
import com.ledgerline.entity.Role;
import com.ledgerline.exception.AccountNotFoundException;
import com.ledgerline.repository.AccountRepository;
import com.ledgerline.security.AuthenticatedUser;
import com.ledgerline.security.UserAuthentication;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Ownership logic without Spring (no proxies, so @PreAuthorize is not applied here; see AccountMethodSecurityTest).
 * Proves the owner always comes from the security context and customer reads use only owner-scoped queries.
 */
class AccountServiceTest {

    private static final UUID CALLER = UUID.randomUUID();

    private final AccountRepository accounts = mock(AccountRepository.class);
    private final AccountService service = new AccountService(accounts);

    static void signIn(UUID id, Role role) {
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "HS256").subject(id.toString()).build();
        SecurityContextHolder.getContext().setAuthentication(new UserAuthentication(
                new AuthenticatedUser(id, "Test User", "t@example.test", role, Instant.now()), jwt));
    }

    @BeforeEach
    void signInAsCaller() {
        signIn(CALLER, Role.ACCOUNT_HOLDER);
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void createUsesTheAuthenticatedUserAsOwner() {
        when(accounts.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
        service.create(new CreateAccountRequest("Savings"));
        ArgumentCaptor<Account> saved = ArgumentCaptor.forClass(Account.class);
        verify(accounts).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getOwnerId()).isEqualTo(CALLER);
        assertThat(saved.getValue().getName()).isEqualTo("Savings");
    }

    @Test
    void getOwnUsesTheOwnerScopedQueryOnlyAndTreatsAnotherUsersAccountAsNotFound() {
        UUID someoneElsesAccount = UUID.randomUUID();
        when(accounts.findByIdAndOwnerId(someoneElsesAccount, CALLER)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getOwn(someoneElsesAccount)).isInstanceOf(AccountNotFoundException.class);
        verify(accounts).findByIdAndOwnerId(someoneElsesAccount, CALLER);
        verifyNoMoreInteractions(accounts);          // never the unscoped findById
    }

    @Test
    void listOwnUsesTheOwnerScopedQueryOnly() {
        when(accounts.findByOwnerId(eq(CALLER), any(Pageable.class))).thenReturn(Page.empty());
        assertThat(service.listOwn(0, 20).items()).isEmpty();
        verify(accounts).findByOwnerId(eq(CALLER), any(Pageable.class));
        verifyNoMoreInteractions(accounts);          // never the unscoped findAll
    }

    @Test
    void withoutAnAuthenticatedUserNothingIsQueried() {
        SecurityContextHolder.clearContext();
        assertThatThrownBy(() -> service.getOwn(UUID.randomUUID())).isInstanceOf(IllegalStateException.class);
        verifyNoMoreInteractions(accounts);
    }
}
