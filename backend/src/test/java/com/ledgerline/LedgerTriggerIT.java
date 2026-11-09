package com.ledgerline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.ledgerline.support.LedgerApi;
import com.ledgerline.support.OwnerJdbc;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The deferred constraint triggers, exercised by writing ledger rows directly as the table owner (bypassing the
 * posting functions) inside explicit transactions. Every invalid state must fail at COMMIT with 23514 and leave no
 * row behind; a correctly built posting must commit. Committed rows can never be deleted, so assertions are about
 * the rows each test created, never about table totals.
 */
@SpringBootTest
@AutoConfigureMockMvc
class LedgerTriggerIT {

    private static final UUID SETTLEMENT = UUID.fromString("a0000000-0000-4000-8000-000000000001");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate appJdbc;

    @Autowired
    private Environment environment;

    private JdbcTemplate ownerJdbc;
    private Connection owner;
    private UUID userId;
    private UUID customer;

    @BeforeEach
    void setUp() throws SQLException {
        ownerJdbc = OwnerJdbc.forTestDatabase(environment);
        LedgerApi api = new LedgerApi(mvc, appJdbc, ownerJdbc);
        LedgerApi.TestUser user = api.user("ACCOUNT_HOLDER");
        userId = user.id();
        customer = api.account(user);                       // balance 0, no lines
        DataSource dataSource = ownerJdbc.getDataSource();
        owner = dataSource.getConnection();
        owner.setAutoCommit(false);
    }

    @AfterEach
    void close() throws SQLException {
        owner.rollback();
        owner.close();
    }

    // ---- low-level helpers (owner connection, inside the open transaction) ---------------------------------------

    private void exec(String sql, Object... args) throws SQLException {
        try (PreparedStatement statement = owner.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                statement.setObject(i + 1, args[i]);
            }
            statement.execute();
        }
    }

    private long[] lockAndRead(UUID account) throws SQLException {
        try (PreparedStatement statement = owner.prepareStatement(
                "select balance_minor, last_seq from accounts where id = ? for update")) {
            statement.setObject(1, account);
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                return new long[] {rs.getLong(1), rs.getLong(2)};
            }
        }
    }

    private UUID key() throws SQLException {
        return key(userId, "DEPOSIT");
    }

    private UUID key(UUID user, String operation) throws SQLException {
        UUID id = UUID.randomUUID();
        byte[] hash = new byte[32];
        new SecureRandom().nextBytes(hash);
        exec("insert into idempotency_keys (id, user_id, idem_key, operation, request_hash) values (?, ?, ?, ?, ?)",
                id, user, "manual-" + id, operation, hash);
        return id;
    }

    private UUID journal(UUID key, String kind, long amount) throws SQLException {
        UUID id = UUID.randomUUID();
        exec("insert into journal_transactions (id, kind, amount_minor, initiated_by, idempotency_key_id) values (?, ?, ?, ?, ?)",
                id, kind, amount, userId, key);
        return id;
    }

    /** One correctly chained entry: lock, update balance and counter, insert the entry and its statement line. */
    private void entry(UUID journal, UUID account, String direction, long amount) throws SQLException {
        long[] state = lockAndRead(account);
        long after = state[0] + ("CREDIT".equals(direction) ? amount : -amount);
        long seq = state[1] + 1;
        exec("update accounts set balance_minor = ?, last_seq = ? where id = ?", after, seq, account);
        UUID entry = UUID.randomUUID();
        exec("insert into ledger_entries (id, transaction_id, account_id, direction, amount_minor, balance_after_minor) "
                + "values (?, ?, ?, ?, ?, ?)", entry, journal, account, direction, amount, after);
        exec("insert into account_statement_lines (account_id, seq, ledger_entry_id) values (?, ?, ?)", account, seq, entry);
    }

    private void refusal(UUID key, UUID account, long amount) throws SQLException {
        refusal(key, account, amount, userId);
    }

    private void refusal(UUID key, UUID account, long amount, UUID initiator) throws SQLException {
        long[] state = lockAndRead(account);
        long seq = state[1] + 1;
        exec("update accounts set last_seq = ? where id = ?", seq, account);
        UUID refusal = UUID.randomUUID();
        exec("insert into refused_attempts (id, kind, account_id, counterparty_account_id, amount_minor, reason, "
                + "balance_at_attempt_minor, initiated_by, idempotency_key_id) values (?, 'WITHDRAWAL', ?, ?, ?, "
                + "'INSUFFICIENT_FUNDS', ?, ?, ?)", refusal, account, SETTLEMENT, amount, state[0], initiator, key);
        exec("insert into account_statement_lines (account_id, seq, refused_attempt_id) values (?, ?, ?)", account, seq, refusal);
    }

    /** Commits and returns the failure (or null if the commit succeeded). */
    private SQLException commit() {
        return catchThrowableOfType(SQLException.class, () -> owner.commit());
    }

    private void assertCommitFails(String messagePart) {
        SQLException failure = commit();
        assertThat((Throwable) failure).as("commit must fail").isNotNull();
        assertThat(failure.getSQLState()).isEqualTo("23514");
        assertThat(failure.getMessage()).contains(messagePart);
    }

    private void assertNothingPersistedFor(UUID journalOrKeyOwner, long settlementBalanceBefore) {
        assertThat(appJdbc.queryForObject("select count(*) from journal_transactions where initiated_by = ?", Integer.class, userId)).isZero();
        assertThat(appJdbc.queryForObject("select count(*) from refused_attempts where initiated_by = ?", Integer.class, userId)).isZero();
        assertThat(ownerJdbc.queryForObject("select count(*) from idempotency_keys where user_id = ?", Integer.class, userId)).isZero();
        assertThat(appJdbc.queryForObject("select count(*) from account_statement_lines where account_id = ?", Integer.class, customer)).isZero();
        assertThat(appJdbc.queryForObject("select balance_minor from accounts where id = ?", Long.class, customer)).isZero();
        assertThat(appJdbc.queryForObject("select last_seq from accounts where id = ?", Long.class, customer)).isZero();
        assertThat(appJdbc.queryForObject("select balance_minor from accounts where id = ?", Long.class, SETTLEMENT))
                .as("settlement balance unchanged").isEqualTo(settlementBalanceBefore);
    }

    private long settlementBalance() {
        return appJdbc.queryForObject("select balance_minor from accounts where id = ?", Long.class, SETTLEMENT);
    }

    // ---- the eight required cases ---------------------------------------------------------------------------------

    @Test
    void case1JournalWithNoEntriesFailsAtCommit() throws SQLException {
        long settlement = settlementBalance();
        journal(key(), "DEPOSIT", 500);
        assertCommitFails("exactly one debit and one credit");
        assertNothingPersistedFor(userId, settlement);
    }

    @Test
    void case2JournalWithOneEntryFailsAtCommit() throws SQLException {
        long settlement = settlementBalance();
        UUID journal = journal(key(), "DEPOSIT", 500);
        entry(journal, SETTLEMENT, "DEBIT", 500);
        assertCommitFails("exactly one debit and one credit");
        assertNothingPersistedFor(userId, settlement);
    }

    @Test
    void case3UnequalDebitAndCreditFailsAtCommit() throws SQLException {
        long settlement = settlementBalance();
        UUID journal = journal(key(), "DEPOSIT", 500);
        entry(journal, SETTLEMENT, "DEBIT", 500);
        entry(journal, customer, "CREDIT", 400);
        assertCommitFails("do not equal credits");
        assertNothingPersistedFor(userId, settlement);
    }

    @Test
    void case4EntriesThatDoNotMatchTheJournalAmountFailAtCommit() throws SQLException {
        long settlement = settlementBalance();
        UUID journal = journal(key(), "DEPOSIT", 500);
        entry(journal, SETTLEMENT, "DEBIT", 400);
        entry(journal, customer, "CREDIT", 400);
        assertCommitFails("must equal the journal amount");
        assertNothingPersistedFor(userId, settlement);
    }

    @Test
    void case5IdempotencyKeyWithNoOutcomeFailsAtCommit() throws SQLException {
        long settlement = settlementBalance();
        key();
        assertCommitFails("has 0 outcomes");
        assertNothingPersistedFor(userId, settlement);
    }

    @Test
    void case6IdempotencyKeyWithBothOutcomesFailsAtCommit() throws SQLException {
        long settlement = settlementBalance();
        UUID key = key();
        UUID journal = journal(key, "DEPOSIT", 500);
        entry(journal, SETTLEMENT, "DEBIT", 500);
        entry(journal, customer, "CREDIT", 500);
        refusal(key, customer, 9_999);
        assertCommitFails("has 2 outcomes");
        assertNothingPersistedFor(userId, settlement);
    }

    @Test
    void case7ACorrectlyBuiltPostingCommitsAndReconciles() throws SQLException {
        long settlement = settlementBalance();
        UUID journal = journal(key(), "DEPOSIT", 500);
        entry(journal, SETTLEMENT, "DEBIT", 500);
        entry(journal, customer, "CREDIT", 500);
        refusal(key(userId, "WITHDRAWAL"), customer, 9_999);   // and a valid refused attempt after it
        assertThat((Throwable) commit()).as("a valid posting commits").isNull();

        assertThat(appJdbc.queryForObject("select balance_minor from accounts where id = ?", Long.class, customer)).isEqualTo(500);
        assertThat(appJdbc.queryForObject("select last_seq from accounts where id = ?", Long.class, customer)).isEqualTo(2);
        assertThat(settlementBalance()).isEqualTo(settlement - 500);
        assertThat(appJdbc.queryForList("select check_name from ledger_reconciliation()", String.class)).isEmpty();
    }

    @Test
    void case8RollbackAfterAPartialPostingLeavesNothing() throws SQLException {
        long settlement = settlementBalance();
        UUID journal = journal(key(), "DEPOSIT", 500);
        entry(journal, SETTLEMENT, "DEBIT", 500);
        owner.rollback();
        assertNothingPersistedFor(userId, settlement);
    }

    // ---- further invariants ---------------------------------------------------------------------------------------

    @Test
    void aBalanceChangeWithoutALedgerEntryFailsAtCommit() throws SQLException {
        long settlement = settlementBalance();
        exec("update accounts set balance_minor = 700 where id = ?", customer);
        assertCommitFails("balance does not match its latest ledger entry");
        assertNothingPersistedFor(userId, settlement);
    }

    @Test
    void aBrokenBalanceChainFailsAtCommit() throws SQLException {
        long settlement = settlementBalance();
        UUID journal = journal(key(), "DEPOSIT", 500);
        entry(journal, SETTLEMENT, "DEBIT", 500);
        // Credit entry claims a balance_after that is not previous balance + amount (and the account agrees with it).
        exec("update accounts set balance_minor = 900, last_seq = 1 where id = ?", customer);
        UUID entry = UUID.randomUUID();
        exec("insert into ledger_entries (id, transaction_id, account_id, direction, amount_minor, balance_after_minor) "
                + "values (?, ?, ?, 'CREDIT', 500, 900)", entry, journal, customer);
        exec("insert into account_statement_lines (account_id, seq, ledger_entry_id) values (?, 1, ?)", customer, entry);
        assertCommitFails("previous balance");
        assertNothingPersistedFor(userId, settlement);
    }

    @Test
    void aJournalWhoseAccountKindsDoNotMatchItsTypeFailsAtCommit() throws SQLException {
        long settlement = settlementBalance();
        UUID journal = journal(key(userId, "TRANSFER"), "TRANSFER", 500);   // a "transfer" out of the settlement account
        entry(journal, SETTLEMENT, "DEBIT", 500);
        entry(journal, customer, "CREDIT", 500);
        assertCommitFails("account kinds do not match");
        assertNothingPersistedFor(userId, settlement);
    }

    @Test
    void aStatementLineThatLeavesAGapIsRejectedImmediately() throws SQLException {
        UUID key = key();
        UUID refusal = UUID.randomUUID();
        exec("insert into refused_attempts (id, kind, account_id, amount_minor, reason, balance_at_attempt_minor, "
                + "initiated_by, idempotency_key_id) values (?, 'WITHDRAWAL', ?, 1, 'INSUFFICIENT_FUNDS', 0, ?, ?)",
                refusal, customer, userId, key);
        SQLException failure = catchThrowableOfType(SQLException.class, () -> exec(
                "insert into account_statement_lines (account_id, seq, refused_attempt_id) values (?, 2, ?)", customer, refusal));
        assertThat((Throwable) failure).isNotNull();
        assertThat(failure.getSQLState()).isEqualTo("23514");
        assertThat(failure.getMessage()).contains("leaves a gap");
    }

    @Test
    void aNegativeCustomerBalanceIsRejectedImmediately() throws SQLException {
        SQLException failure = catchThrowableOfType(SQLException.class,
                () -> exec("update accounts set balance_minor = -1 where id = ?", customer));
        assertThat(failure.getSQLState()).isEqualTo("23514");
        assertThat(failure.getMessage()).contains("ck_accounts_balance_range");
    }

    @Test
    void reconciliationIndependentlyDetectsTamperingWhenTriggersAreBypassed() throws SQLException {
        // Simulates corruption that the triggers normally make impossible: the owner disables its own triggers inside
        // a transaction that is rolled back afterwards, so nothing persists.
        exec("alter table accounts disable trigger user");
        exec("update accounts set balance_minor = 123 where id = ?", customer);
        try (PreparedStatement statement = owner.prepareStatement("select check_name, subject_id from ledger_reconciliation()");
             ResultSet rs = statement.executeQuery()) {
            java.util.List<String> found = new java.util.ArrayList<>();
            while (rs.next()) {
                if (customer.equals(rs.getObject(2, UUID.class)) || rs.getObject(2) == null) {
                    found.add(rs.getString(1));
                }
            }
            assertThat(found).contains("ACCOUNT_BALANCE", "TOTAL_BALANCE");
        }
        owner.rollback();
        assertThat(appJdbc.queryForObject("select balance_minor from accounts where id = ?", Long.class, customer)).isZero();
    }

    // ---- V6 hardening (L3, L4) --------------------------------------------------------------------------------------

    private UUID someOtherUser() {
        return appJdbc.queryForObject("select owner_id from accounts where kind = 'CUSTOMER' and owner_id <> ? limit 1",
                UUID.class, userId);
    }

    @Test
    void aJournalWhoseInitiatorIsNotTheKeysUserFailsAtCommit() throws SQLException {
        long settlement = settlementBalance();
        UUID journal = journal(key(someOtherUser(), "DEPOSIT"), "DEPOSIT", 500);   // the key belongs to someone else
        entry(journal, SETTLEMENT, "DEBIT", 500);
        entry(journal, customer, "CREDIT", 500);
        assertCommitFails("does not match its user or operation");
        assertNothingPersistedFor(userId, settlement);
    }

    @Test
    void aJournalWhoseKindIsNotTheKeysOperationFailsAtCommit() throws SQLException {
        long settlement = settlementBalance();
        UUID journal = journal(key(userId, "WITHDRAWAL"), "DEPOSIT", 500);
        entry(journal, SETTLEMENT, "DEBIT", 500);
        entry(journal, customer, "CREDIT", 500);
        assertCommitFails("does not match its user or operation");
        assertNothingPersistedFor(userId, settlement);
    }

    @Test
    void aJournalOnAnAccountTheInitiatorDoesNotOwnFailsAtCommit() throws SQLException {
        long settlement = settlementBalance();
        UUID strangersAccount = appJdbc.queryForObject(
                "select id from accounts where kind = 'CUSTOMER' and owner_id <> ? limit 1", UUID.class, userId);
        long strangersBalance = appJdbc.queryForObject("select balance_minor from accounts where id = ?", Long.class, strangersAccount);
        UUID journal = journal(key(), "DEPOSIT", 500);
        entry(journal, SETTLEMENT, "DEBIT", 500);
        entry(journal, strangersAccount, "CREDIT", 500);
        assertCommitFails("does not own the account");
        assertThat(settlementBalance()).isEqualTo(settlement);
        assertThat(appJdbc.queryForObject("select balance_minor from accounts where id = ?", Long.class, strangersAccount))
                .isEqualTo(strangersBalance);
    }

    @Test
    void aRefusalOnTheSettlementAccountFailsAtCommit() throws SQLException {
        long settlement = settlementBalance();
        long settlementLines = appJdbc.queryForObject("select last_seq from accounts where id = ?", Long.class, SETTLEMENT);
        refusal(key(userId, "WITHDRAWAL"), SETTLEMENT, 1);
        assertCommitFails("customer account owned by its initiator");
        assertThat(appJdbc.queryForObject("select last_seq from accounts where id = ?", Long.class, SETTLEMENT)).isEqualTo(settlementLines);
        assertNothingPersistedFor(userId, settlement);
    }

    @Test
    void aRefusalOnSomeoneElsesAccountFailsAtCommit() throws SQLException {
        long settlement = settlementBalance();
        UUID other = someOtherUser();
        refusal(key(other, "WITHDRAWAL"), customer, 1, other);      // customer belongs to userId, not to other
        assertCommitFails("customer account owned by its initiator");
        assertNothingPersistedFor(userId, settlement);
    }

    @Test
    void onlyTheSettlementAccountCanBeASystemAccount() {
        SQLException failure = catchThrowableOfType(SQLException.class, () -> exec(
                "insert into accounts (id, owner_id, name, kind) values (?, null, 'second system', 'SYSTEM')", UUID.randomUUID()));
        assertThat((Throwable) failure).isNotNull();
        assertThat(failure.getSQLState()).isIn("23514", "23505");   // check (fixed id) or the unique index
        assertThat(appJdbc.queryForObject("select count(*) from accounts where kind = 'SYSTEM'", Integer.class)).isEqualTo(1);
    }
}
