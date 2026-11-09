package com.ledgerline.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;

import com.ledgerline.config.SecurityConfig;
import com.ledgerline.dto.CreateAccountRequest;
import com.ledgerline.entity.Role;
import com.ledgerline.repository.AccountRepository;
import com.ledgerline.service.AccountOversightService;
import com.ledgerline.service.AccountService;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

/**
 * {@code @PreAuthorize} through the real Spring proxies, with method security switched on by the application's own
 * SecurityConfig (not a test copy). No web layer and no database: the repository is a mock, and the tests check it
 * is never reached when access is denied.
 */
@SpringJUnitConfig(classes = {SecurityConfig.class, AccountService.class, AccountOversightService.class})
class AccountMethodSecurityTest {

    @Autowired
    private AccountService accounts;

    @Autowired
    private AccountOversightService oversight;

    @MockitoBean
    private AccountRepository repository;

    private static void signInAs(Role role) {
        UUID id = UUID.randomUUID();
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "HS256").subject(id.toString()).build();
        SecurityContextHolder.getContext().setAuthentication(
                new UserAuthentication(new AuthenticatedUser(id, "T", "t@example.test", role, Instant.now()), jwt));
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void theServicesAreProxied() {
        assertThat(AopUtils.isAopProxy(accounts)).isTrue();
        assertThat(AopUtils.isAopProxy(oversight)).isTrue();
    }

    @Test
    void anAccountHolderCanUseCustomerMethodsButNotOversight() {
        signInAs(Role.ACCOUNT_HOLDER);
        given(repository.findByOwnerId(any(), any())).willReturn(Page.empty());
        assertThat(accounts.listOwn(0, 20).items()).isEmpty();
        assertThatThrownBy(() -> oversight.listAll(0, 20)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> oversight.get(UUID.randomUUID())).isInstanceOf(AccessDeniedException.class);
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"ADMIN", "AUDITOR"})
    void staffCannotUseCustomerMethodsAndNothingIsQueried(Role role) {
        signInAs(role);
        assertThatThrownBy(() -> accounts.create(new CreateAccountRequest("x"))).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> accounts.listOwn(0, 20)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> accounts.getOwn(UUID.randomUUID())).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(repository);
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"ADMIN", "AUDITOR"})
    void staffCanUseOversight(Role role) {
        signInAs(role);
        given(repository.findById(any())).willReturn(Optional.empty());
        given(repository.findAll(any(org.springframework.data.domain.Pageable.class))).willReturn(Page.empty());
        assertThat(oversight.listAll(0, 20).items()).isEmpty();
    }

    @Test
    void withoutAuthenticationEveryMethodIsRefusedBeforeAnyQuery() {
        assertThatThrownBy(() -> accounts.listOwn(0, 20)).isInstanceOf(AuthenticationCredentialsNotFoundException.class);
        assertThatThrownBy(() -> oversight.listAll(0, 20)).isInstanceOf(AuthenticationCredentialsNotFoundException.class);
        verifyNoInteractions(repository);
    }
}
