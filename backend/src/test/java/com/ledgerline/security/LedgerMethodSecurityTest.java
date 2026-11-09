package com.ledgerline.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;

import com.ledgerline.config.SecurityConfig;
import com.ledgerline.entity.Role;
import com.ledgerline.repository.AccountRepository;
import com.ledgerline.service.LedgerService;
import com.ledgerline.service.ReconciliationService;
import com.ledgerline.service.StatementService;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

/** @PreAuthorize on the ledger services, through real proxies created by the application's SecurityConfig. */
@SpringJUnitConfig(classes = {SecurityConfig.class, LedgerService.class, StatementService.class, ReconciliationService.class})
class LedgerMethodSecurityTest {

    @Autowired
    private LedgerService ledger;

    @Autowired
    private StatementService statements;

    @Autowired
    private ReconciliationService reconciliation;

    @MockitoBean
    private AccountRepository accounts;

    @MockitoBean
    private JdbcTemplate jdbc;

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
        assertThat(AopUtils.isAopProxy(ledger)).isTrue();
        assertThat(AopUtils.isAopProxy(statements)).isTrue();
        assertThat(AopUtils.isAopProxy(reconciliation)).isTrue();
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"ADMIN", "AUDITOR"})
    void staffCannotMoveMoneyOrReadOwnStatementsAndNothingIsQueried(Role role) {
        signInAs(role);
        UUID account = UUID.randomUUID();
        assertThatThrownBy(() -> ledger.deposit(account, 1, null, "k")).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> ledger.withdraw(account, 1, null, "k")).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> ledger.transfer(account, UUID.randomUUID(), 1, null, "k")).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> statements.own(account, 0, 10)).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(accounts, jdbc);
    }

    @Test
    void anAccountHolderCannotUseOversight() {
        signInAs(Role.ACCOUNT_HOLDER);
        assertThatThrownBy(() -> statements.any(UUID.randomUUID(), 0, 10)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> reconciliation.reconcile()).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(accounts, jdbc);
    }
}
