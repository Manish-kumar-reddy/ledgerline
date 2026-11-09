package com.ledgerline.service;

import com.ledgerline.exception.AccountNotFoundException;
import com.ledgerline.exception.IdempotencyConflictException;
import com.ledgerline.exception.InvalidLedgerRequestException;
import com.ledgerline.exception.LedgerBusyException;
import com.ledgerline.repository.AccountRepository;
import com.ledgerline.security.CurrentUser;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

/**
 * Deposits, withdrawals and transfers (ACCOUNT_HOLDER only).
 *
 * Trust boundary (THREATS.md): the initiator is always the authenticated user from {@link CurrentUser}; no request
 * field can name it. This service first checks, with the owner-scoped query, that the caller owns the account being
 * debited or deposited into (404 otherwise, exactly as for an unknown account). It then calls one PostgreSQL posting
 * function, which re-checks ownership, status, funds and limits for that initiator under row locks and records the
 * outcome. The function cannot authenticate the HTTP user itself: it trusts the initiator id this class passes.
 *
 * Deliberately not {@code @Transactional}: each posting function call runs in its own database transaction
 * (auto-commit), which contains the idempotency key, the locks, the outcome and the balance changes together.
 */
@Service
public class LedgerService {

    private static final Log securityLog = LogFactory.getLog("ledgerline.security");
    private static final Log log = LogFactory.getLog(LedgerService.class);

    /** Lock timeout, deadlock, query cancelled: the whole posting was rolled back and may be retried. */
    private static final Set<String> RETRYABLE = Set.of("55P03", "40P01", "57014");

    private static final RowMapper<PostingResult> ROW = (rs, n) -> new PostingResult(
            rs.getString("r_outcome"), rs.getBoolean("r_replayed"), rs.getString("r_kind"),
            rs.getObject("r_journal_id", UUID.class), rs.getObject("r_refused_id", UUID.class),
            rs.getString("r_reason"), rs.getObject("r_account_id", UUID.class),
            rs.getObject("r_counterparty_account_id", UUID.class), rs.getLong("r_amount_minor"),
            rs.getLong("r_balance_minor"), rs.getLong("r_seq"),
            rs.getObject("r_occurred_at", OffsetDateTime.class).toInstant(), rs.getObject("r_initiated_by", UUID.class));

    private final AccountRepository accounts;
    private final JdbcTemplate jdbc;

    public LedgerService(AccountRepository accounts, JdbcTemplate jdbc) {
        this.accounts = accounts;
        this.jdbc = jdbc;
    }

    @PreAuthorize("hasRole('ACCOUNT_HOLDER')")
    public PostingResult deposit(UUID accountId, long amountMinor, String description, String idempotencyKey) {
        UUID initiator = requireOwnAccount(accountId, "deposit");
        return post("select * from public.ledger_deposit(?, ?, ?, ?, ?)",
                initiator, accountId, amountMinor, description, idempotencyKey);
    }

    @PreAuthorize("hasRole('ACCOUNT_HOLDER')")
    public PostingResult withdraw(UUID accountId, long amountMinor, String description, String idempotencyKey) {
        UUID initiator = requireOwnAccount(accountId, "withdrawal");
        return post("select * from public.ledger_withdraw(?, ?, ?, ?, ?)",
                initiator, accountId, amountMinor, description, idempotencyKey);
    }

    @PreAuthorize("hasRole('ACCOUNT_HOLDER')")
    public PostingResult transfer(UUID fromAccountId, UUID toAccountId, long amountMinor, String description,
            String idempotencyKey) {
        if (fromAccountId.equals(toAccountId)) {
            throw new InvalidLedgerRequestException("A transfer needs two different accounts.");
        }
        UUID initiator = requireOwnAccount(fromAccountId, "transfer");
        // The destination is not checked here: the posting function refuses a missing, inactive or system
        // destination with one reason (DESTINATION_UNAVAILABLE), recorded on the sender's own statement.
        return post("select * from public.ledger_transfer(?, ?, ?, ?, ?, ?)",
                initiator, fromAccountId, toAccountId, amountMinor, description, idempotencyKey);
    }

    /** The caller's id, after checking they own the account; otherwise the same 404 as an unknown account. */
    private UUID requireOwnAccount(UUID accountId, String operation) {
        UUID caller = CurrentUser.require().id();
        if (accounts.findByIdAndOwnerId(accountId, caller).isEmpty()) {
            securityLog.warn("Refused " + operation + ": user " + caller + " does not own account " + accountId);
            throw new AccountNotFoundException();
        }
        return caller;
    }

    private PostingResult post(String sql, Object... args) {
        List<PostingResult> rows;
        try {
            rows = jdbc.query(sql, ROW, args);
        } catch (DataAccessException ex) {
            throw translate(ex, (UUID) args[0]);
        }
        if (rows.size() != 1) {
            throw new IllegalStateException("Posting function returned " + rows.size() + " rows");
        }
        return rows.getFirst();
    }

    private static RuntimeException translate(DataAccessException ex, UUID initiator) {
        String state = ex.getMostSpecificCause() instanceof SQLException sql ? sql.getSQLState() : null;
        if ("LL409".equals(state)) {
            securityLog.info("Idempotency key reused with a different request by user " + initiator);
            return new IdempotencyConflictException();
        }
        if (state != null && RETRYABLE.contains(state)) {
            log.warn("Posting rolled back and may be retried (SQLSTATE " + state + ")");
            return new LedgerBusyException();
        }
        if ("LL401".equals(state) || "LL403".equals(state) || "LL400".equals(state)) {
            // The API checked all of this before calling; the database disagreeing means a bug or tampering.
            securityLog.error("Posting function refused a request the API had allowed (SQLSTATE " + state
                    + ", user " + initiator + ")");
        }
        return ex;
    }
}
