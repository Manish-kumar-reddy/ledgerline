package com.ledgerline.service;

import com.ledgerline.dto.AccountResponse;
import com.ledgerline.dto.StatementLineResponse;
import com.ledgerline.dto.StatementResponse;
import com.ledgerline.entity.Account;
import com.ledgerline.exception.AccountNotFoundException;
import com.ledgerline.money.Money;
import com.ledgerline.repository.AccountRepository;
import com.ledgerline.security.CurrentUser;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Account statements: every posted DEBIT/CREDIT and every refused attempt, in the account's own sequence order, with
 * the running balance stored at the time (under the row lock) and the initiator. Read in one REPEATABLE READ
 * transaction so the account header and the lines come from the same snapshot.
 */
@Service
public class StatementService {

    private static final Log securityLog = LogFactory.getLog("ledgerline.security");

    private static final String LINES = """
            select l.seq,
                   case when l.ledger_entry_id is not null then 'POSTED' else 'REFUSED' end as status,
                   coalesce(j.kind, r.kind) as operation,
                   e.direction,
                   coalesce(e.amount_minor, r.amount_minor) as amount_minor,
                   coalesce(e.balance_after_minor, r.balance_at_attempt_minor) as balance_minor,
                   r.reason,
                   coalesce(j.initiated_by, r.initiated_by) as initiated_by,
                   coalesce(other.account_id, r.counterparty_account_id) as counterparty_account_id,
                   j.id as transaction_id,
                   r.id as refused_attempt_id,
                   coalesce(j.description, r.description) as description,
                   coalesce(j.posted_at, r.attempted_at) as occurred_at
              from public.account_statement_lines l
              left join public.ledger_entries e on e.id = l.ledger_entry_id
              left join public.journal_transactions j on j.id = e.transaction_id
              left join public.ledger_entries other on other.transaction_id = j.id and other.id <> e.id
              left join public.refused_attempts r on r.id = l.refused_attempt_id
             where l.account_id = ?
             order by l.seq
             limit ? offset ?""";

    private static final RowMapper<StatementLineResponse> LINE = (rs, n) -> new StatementLineResponse(
            rs.getLong("seq"), rs.getString("status"), rs.getString("operation"), rs.getString("direction"),
            Money.format(rs.getLong("amount_minor")), rs.getLong("amount_minor"),
            Money.format(rs.getLong("balance_minor")), rs.getLong("balance_minor"), rs.getString("reason"),
            rs.getObject("initiated_by", UUID.class), rs.getObject("counterparty_account_id", UUID.class),
            rs.getObject("transaction_id", UUID.class), rs.getObject("refused_attempt_id", UUID.class),
            rs.getString("description"), rs.getObject("occurred_at", OffsetDateTime.class).toInstant());

    private final AccountRepository accounts;
    private final JdbcTemplate jdbc;

    public StatementService(AccountRepository accounts, JdbcTemplate jdbc) {
        this.accounts = accounts;
        this.jdbc = jdbc;
    }

    /** The caller's own account only; any other id is the same 404 as an unknown one. */
    @PreAuthorize("hasRole('ACCOUNT_HOLDER')")
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public StatementResponse own(UUID accountId, int page, int size) {
        UUID caller = CurrentUser.require().id();
        Account account = accounts.findByIdAndOwnerId(accountId, caller).orElseThrow(() -> {
            securityLog.warn("Refused statement: user " + caller + " does not own account " + accountId);
            return new AccountNotFoundException();
        });
        return build(account, page, size);
    }

    /** Oversight: any account, including the settlement account. Read-only. */
    @PreAuthorize("hasAnyRole('ADMIN', 'AUDITOR')")
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public StatementResponse any(UUID accountId, int page, int size) {
        return build(accounts.findById(accountId).orElseThrow(AccountNotFoundException::new), page, size);
    }

    private StatementResponse build(Account account, int page, int size) {
        List<StatementLineResponse> lines = jdbc.query(LINES, LINE, account.getId(), size, (long) page * size);
        long total = account.getLastSeq();
        return new StatementResponse(AccountResponse.from(account), lines, page, size, total,
                (int) ((total + size - 1) / size));
    }
}
