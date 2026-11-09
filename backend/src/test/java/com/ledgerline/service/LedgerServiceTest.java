package com.ledgerline.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ledgerline.entity.Account;
import com.ledgerline.entity.Role;
import com.ledgerline.exception.AccountNotFoundException;
import com.ledgerline.exception.IdempotencyConflictException;
import com.ledgerline.exception.InvalidLedgerRequestException;
import com.ledgerline.exception.LedgerBusyException;
import com.ledgerline.repository.AccountRepository;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.core.context.SecurityContextHolder;

/** The initiator comes only from the security context; ownership is checked before the database is called. */
class LedgerServiceTest {

    private static final UUID CALLER = UUID.randomUUID();
    private static final UUID ACCOUNT = UUID.randomUUID();

    private final AccountRepository accounts = mock(AccountRepository.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final LedgerService service = new LedgerService(accounts, jdbc);

    @BeforeEach
    void signIn() {
        AccountServiceTest.signIn(CALLER, Role.ACCOUNT_HOLDER);
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @SuppressWarnings("unchecked")
    private void functionReturnsOneRow() {
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of(mock(PostingResult.class)));
    }

    @Test
    @SuppressWarnings("unchecked")
    void theInitiatorPassedToPostgreSqlIsTheAuthenticatedUser() {
        when(accounts.findByIdAndOwnerId(ACCOUNT, CALLER)).thenReturn(Optional.of(mock(Account.class)));
        functionReturnsOneRow();
        service.withdraw(ACCOUNT, 1000, null, "k");
        verify(jdbc).query(eq("select * from public.ledger_withdraw(?, ?, ?, ?, ?)"), any(RowMapper.class),
                eq(CALLER), eq(ACCOUNT), eq(1000L), eq(null), eq("k"));
    }

    @Test
    void anAccountTheCallerDoesNotOwnIsNotFoundAndTheDatabaseIsNotCalled() {
        when(accounts.findByIdAndOwnerId(ACCOUNT, CALLER)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.deposit(ACCOUNT, 1, null, "k")).isInstanceOf(AccountNotFoundException.class);
        assertThatThrownBy(() -> service.transfer(ACCOUNT, UUID.randomUUID(), 1, null, "k"))
                .isInstanceOf(AccountNotFoundException.class);
        verifyNoInteractions(jdbc);
    }

    @Test
    void aTransferToTheSameAccountIs400() {
        assertThatThrownBy(() -> service.transfer(ACCOUNT, ACCOUNT, 1, null, "k")).isInstanceOf(InvalidLedgerRequestException.class);
        verifyNoInteractions(jdbc, accounts);
    }

    @Test
    @SuppressWarnings("unchecked")
    void sqlStatesAreTranslated() {
        when(accounts.findByIdAndOwnerId(ACCOUNT, CALLER)).thenReturn(Optional.of(mock(Account.class)));
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenThrow(new DataAccessResourceFailureException("x", new SQLException("reused", "LL409")));
        assertThatThrownBy(() -> service.deposit(ACCOUNT, 1, null, "k")).isInstanceOf(IdempotencyConflictException.class);

        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenThrow(new PessimisticLockingFailureException("x", new SQLException("lock timeout", "55P03")));
        assertThatThrownBy(() -> service.deposit(ACCOUNT, 1, null, "k")).isInstanceOf(LedgerBusyException.class);

        DataAccessResourceFailureException other = new DataAccessResourceFailureException("x", new SQLException("no", "LL403"));
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenThrow(other);
        assertThatThrownBy(() -> service.deposit(ACCOUNT, 1, null, "k")).isSameAs(other);
    }
}
