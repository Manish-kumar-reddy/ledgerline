package com.ledgerline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.ledgerline.support.LedgerApi;
import com.ledgerline.support.OwnerJdbc;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * V5 database security with real SQL: what the runtime role (ledgerline_app) can and cannot do, the append-only
 * guarantee even for the owner, how the posting functions are secured, and direct posting-function calls with
 * invalid inputs and every kind of wrong initiator.
 */
@SpringBootTest
@AutoConfigureMockMvc
class LedgerSchemaIT {

    private static final String SETTLEMENT = "a0000000-0000-4000-8000-000000000001";
    private static final String[] LEDGER_TABLES =
            {"idempotency_keys", "journal_transactions", "ledger_entries", "refused_attempts", "account_statement_lines"};

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate appJdbc;

    @Autowired
    private Environment environment;

    private JdbcTemplate ownerJdbc;
    private LedgerApi api;

    @BeforeEach
    void setUp() {
        ownerJdbc = OwnerJdbc.forTestDatabase(environment);
        api = new LedgerApi(mvc, appJdbc, ownerJdbc);
    }

    private static String sqlStateOf(JdbcTemplate jdbc, String sql, Object... args) {
        DataAccessException failure = catchThrowableOfType(DataAccessException.class, () -> jdbc.queryForList(sql, args));
        assertThat(failure).as("expected PostgreSQL to refuse: %s", sql).isNotNull();
        assertThat(failure.getMostSpecificCause()).isInstanceOf(SQLException.class);
        return ((SQLException) failure.getMostSpecificCause()).getSQLState();
    }

    private static String updateStateOf(JdbcTemplate jdbc, String sql, Object... args) {
        DataAccessException failure = catchThrowableOfType(DataAccessException.class, () -> jdbc.update(sql, args));
        assertThat(failure).as("expected PostgreSQL to refuse: %s", sql).isNotNull();
        return ((SQLException) failure.getMostSpecificCause()).getSQLState();
    }

    private int keysFor(UUID user, String key) {
        return ownerJdbc.queryForObject("select count(*) from idempotency_keys where user_id = ? and idem_key = ?",
                Integer.class, user, key);
    }

    // ---- runtime role ---------------------------------------------------------------------------------------------

    @Test
    void theRuntimeRoleCannotWriteLedgerRowsOrBalances() {
        LedgerApi.TestUser holder = api.user("ACCOUNT_HOLDER");
        UUID account = api.fundedAccount(holder, "10");
        UUID entry = appJdbc.queryForObject("select id from ledger_entries where account_id = ?", UUID.class, account);
        UUID journal = appJdbc.queryForObject("select transaction_id from ledger_entries where id = ?", UUID.class, entry);

        // 42501 = insufficient_privilege
        assertThat(updateStateOf(appJdbc, "update accounts set balance_minor = 99999 where id = ?", account)).isEqualTo("42501");
        assertThat(updateStateOf(appJdbc, "update accounts set last_seq = 0 where id = ?", account)).isEqualTo("42501");
        assertThat(updateStateOf(appJdbc, "insert into accounts (owner_id, name, balance_minor) values (?, 'x', 500)", holder.id())).isEqualTo("42501");
        assertThat(updateStateOf(appJdbc, "insert into accounts (owner_id, name, kind) values (null, 'x', 'SYSTEM')")).isEqualTo("42501");
        assertThat(updateStateOf(appJdbc, "update ledger_entries set amount_minor = 1 where id = ?", entry)).isEqualTo("42501");
        assertThat(updateStateOf(appJdbc, "delete from ledger_entries where id = ?", entry)).isEqualTo("42501");
        assertThat(updateStateOf(appJdbc, "update journal_transactions set amount_minor = 1 where id = ?", journal)).isEqualTo("42501");
        assertThat(updateStateOf(appJdbc, "insert into journal_transactions (kind, amount_minor, initiated_by, idempotency_key_id) "
                + "values ('DEPOSIT', 1, ?, ?)", holder.id(), UUID.randomUUID())).isEqualTo("42501");
        assertThat(updateStateOf(appJdbc, "insert into refused_attempts (kind, account_id, amount_minor, reason, "
                + "balance_at_attempt_minor, initiated_by, idempotency_key_id) values ('DEPOSIT', ?, 1, 'INSUFFICIENT_FUNDS', 0, ?, ?)",
                account, holder.id(), UUID.randomUUID())).isEqualTo("42501");
        assertThat(updateStateOf(appJdbc, "insert into account_statement_lines (account_id, seq) values (?, 99)", account)).isEqualTo("42501");
        assertThat(updateStateOf(appJdbc, "insert into idempotency_keys (user_id, idem_key, operation, request_hash) "
                + "values (?, 'k', 'DEPOSIT', '\\x00')", holder.id())).isEqualTo("42501");
        for (String table : LEDGER_TABLES) {
            assertThat(updateStateOf(appJdbc, "truncate " + table + " cascade")).as(table).isEqualTo("42501");
            assertThat(updateStateOf(appJdbc, "delete from " + table)).as(table).isEqualTo("42501");
        }
        // It cannot even read the idempotency keys.
        assertThat(sqlStateOf(appJdbc, "select * from idempotency_keys limit 1")).isEqualTo("42501");
        assertThat(api.balance(account)).isEqualTo(1000);
    }

    @Test
    void theRuntimeRoleCanCallOnlyThePostingFunctionsAndReconciliation() {
        List<Map<String, Object>> functions = appJdbc.queryForList("""
                select p.proname as name, has_function_privilege(current_user, p.oid, 'EXECUTE') as can_execute
                from pg_proc p join pg_namespace n on n.oid = p.pronamespace
                where n.nspname = 'public' and p.proname like 'ledger%' order by p.proname""");
        assertThat(functions).filteredOn(f -> (Boolean) f.get("can_execute")).extracting(f -> f.get("name"))
                .containsExactlyInAnyOrder("ledger_deposit", "ledger_withdraw", "ledger_transfer", "ledger_reconciliation");
        assertThat(functions).hasSizeGreaterThan(10);
        assertThat(sqlStateOf(appJdbc, "select * from ledger_post('DEPOSIT', ?, ?, null, 1, null, 'k')",
                UUID.randomUUID(), UUID.randomUUID())).isEqualTo("42501");
        assertThat(sqlStateOf(appJdbc, "select ledger_check_account(?)", UUID.fromString(SETTLEMENT))).isEqualTo("42501");
        assertThat(sqlStateOf(appJdbc, "select * from ledger_outcome(?, false)", UUID.randomUUID())).isEqualTo("42501");
    }

    @Test
    void functionsAreOwnedByTheMigrationRoleWithAFixedSearchPathAndNoPublicExecute() {
        String owner = environment.getRequiredProperty("spring.flyway.user");
        List<Map<String, Object>> functions = appJdbc.queryForList("""
                select p.proname as name, pg_get_userbyid(p.proowner) as owner, p.prosecdef as definer,
                       array_to_string(p.proconfig, ';') as config, p.proacl is null as default_acl,
                       exists (select 1 from aclexplode(p.proacl) a where a.grantee = 0) as public_grant
                from pg_proc p join pg_namespace n on n.oid = p.pronamespace
                where n.nspname = 'public' and p.proname like 'ledger%'""");
        assertThat(functions).allSatisfy(f -> {
            assertThat(f.get("owner")).as("%s owner", f.get("name")).isEqualTo(owner);
            assertThat(f.get("config")).as("%s search_path", f.get("name")).isEqualTo("search_path=pg_catalog, pg_temp");
            assertThat(f.get("default_acl")).as("%s has an explicit ACL (no implicit PUBLIC execute)", f.get("name")).isEqualTo(false);
            assertThat(f.get("public_grant")).as("%s PUBLIC execute", f.get("name")).isEqualTo(false);
        });
        assertThat(functions).filteredOn(f -> (Boolean) f.get("definer")).extracting(f -> f.get("name"))
                .containsExactlyInAnyOrder("ledger_post", "ledger_outcome", "ledger_deposit", "ledger_withdraw", "ledger_transfer");
        for (String table : LEDGER_TABLES) {
            assertThat(appJdbc.queryForObject("select tableowner from pg_tables where tablename = ?", String.class, table))
                    .as(table).isEqualTo(owner);
            assertThat(appJdbc.queryForObject("""
                    select count(*) from pg_class c cross join lateral aclexplode(c.relacl) a
                    where c.relname = ? and a.grantee = 0""", Integer.class, table)).as("PUBLIC on %s", table).isZero();
        }
    }

    // ---- append-only, even for the owner --------------------------------------------------------------------------

    @Test
    void theOwnerCannotUpdateDeleteOrTruncatePostedRows() {
        LedgerApi.TestUser holder = api.user("ACCOUNT_HOLDER");
        UUID account = api.fundedAccount(holder, "10");
        api.withdraw(holder, account, "50", "refused-" + UUID.randomUUID());   // a refused attempt too
        UUID entry = appJdbc.queryForObject("select id from ledger_entries where account_id = ?", UUID.class, account);

        // LL900 = the append-only trigger
        assertThat(updateStateOf(ownerJdbc, "update ledger_entries set amount_minor = amount_minor where id = ?", entry)).isEqualTo("LL900");
        assertThat(updateStateOf(ownerJdbc, "delete from ledger_entries where id = ?", entry)).isEqualTo("LL900");
        assertThat(updateStateOf(ownerJdbc, "update journal_transactions set description = 'x' where id = "
                + "(select transaction_id from ledger_entries where id = ?)", entry)).isEqualTo("LL900");
        assertThat(updateStateOf(ownerJdbc, "update refused_attempts set reason = 'INSUFFICIENT_FUNDS' where account_id = ?", account)).isEqualTo("LL900");
        assertThat(updateStateOf(ownerJdbc, "delete from account_statement_lines where account_id = ?", account)).isEqualTo("LL900");
        assertThat(updateStateOf(ownerJdbc, "update idempotency_keys set idem_key = 'x' where user_id = ?", holder.id())).isEqualTo("LL900");
        for (String table : LEDGER_TABLES) {
            assertThat(updateStateOf(ownerJdbc, "truncate " + table + " cascade")).as(table).isEqualTo("LL900");
        }
        // The account's owner and kind are fixed too.
        assertThat(updateStateOf(ownerJdbc, "update accounts set owner_id = null, kind = 'SYSTEM' where id = ?", account)).isEqualTo("LL900");
        assertThat(appJdbc.queryForObject("select count(*) from account_statement_lines where account_id = ?", Integer.class, account)).isEqualTo(2);
    }

    // ---- direct posting-function calls by the runtime role --------------------------------------------------------

    @Test
    void directCallsWithInvalidInputAreRejectedAndLeaveNoKey() {
        LedgerApi.TestUser holder = api.user("ACCOUNT_HOLDER");
        UUID account = api.fundedAccount(holder, "10");
        UUID other = api.fundedAccount(holder, "10");
        Object[][] calls = {
                {"select * from ledger_deposit(?, ?, ?, ?, ?)", holder.id(), account, 0L, null, "zero"},
                {"select * from ledger_deposit(?, ?, ?, ?, ?)", holder.id(), account, -5L, null, "negative"},
                {"select * from ledger_withdraw(?, ?, ?, ?, ?)", holder.id(), account, 100_000_000_000L, null, "huge"},
                {"select * from ledger_withdraw(?, ?, ?, ?, ?)", holder.id(), account, 1L, null, "has space"},
                {"select * from ledger_withdraw(?, ?, ?, ?, ?)", holder.id(), account, 1L, "line\nbreak", "ctrl"},
                {"select * from ledger_withdraw(?, ?, ?, ?, ?)", holder.id(), account, 1L, "", "empty-description"},
                {"select * from ledger_withdraw(?, ?, ?, ?, ?)", holder.id(), account, 1L, null, null},
                {"select * from ledger_transfer(?, ?, ?, ?, ?, ?)", holder.id(), account, account, 1L, null, "self"},
                {"select * from ledger_transfer(?, ?, ?, ?, ?, ?)", holder.id(), account, null, 1L, null, "nowhere"},
        };
        for (Object[] call : calls) {
            Object[] args = java.util.Arrays.copyOfRange(call, 1, call.length);
            assertThat(sqlStateOf(appJdbc, (String) call[0], args)).as("%s", java.util.Arrays.toString(call)).isEqualTo("LL400");
        }
        for (String key : new String[] {"zero", "negative", "huge", "ctrl", "empty-description", "self", "nowhere"}) {
            assertThat(keysFor(holder.id(), key)).as(key).isZero();
        }
        assertThat(api.balance(account)).isEqualTo(1000);
        assertThat(api.balance(other)).isEqualTo(1000);
    }

    @Test
    void directCallsForAnyoneButTheEnabledOwningAccountHolderAreRefused() {
        LedgerApi.TestUser owner = api.user("ACCOUNT_HOLDER");
        UUID account = api.fundedAccount(owner, "10");
        LedgerApi.TestUser otherHolder = api.user("ACCOUNT_HOLDER");
        LedgerApi.TestUser admin = api.user("ADMIN");
        LedgerApi.TestUser auditor = api.user("AUDITOR");
        LedgerApi.TestUser disabled = api.user("ACCOUNT_HOLDER");
        UUID disabledAccount = api.fundedAccount(disabled, "10");
        ownerJdbc.update("update users set enabled = false where id = ?", disabled.id());

        String withdraw = "select * from ledger_withdraw(?, ?, ?, ?, ?)";
        // LL403: not an account holder, or not the owner. LL401: unknown or disabled.
        assertThat(sqlStateOf(appJdbc, withdraw, otherHolder.id(), account, 100L, null, "steal")).isEqualTo("LL403");
        assertThat(sqlStateOf(appJdbc, withdraw, admin.id(), account, 100L, null, "steal")).isEqualTo("LL403");
        assertThat(sqlStateOf(appJdbc, withdraw, auditor.id(), account, 100L, null, "steal")).isEqualTo("LL403");
        assertThat(sqlStateOf(appJdbc, withdraw, disabled.id(), disabledAccount, 100L, null, "steal")).isEqualTo("LL401");
        assertThat(sqlStateOf(appJdbc, withdraw, UUID.randomUUID(), account, 100L, null, "steal")).isEqualTo("LL401");
        assertThat(sqlStateOf(appJdbc, "select * from ledger_transfer(?, ?, ?, ?, ?, ?)",
                otherHolder.id(), account, api.account(otherHolder), 100L, null, "steal")).isEqualTo("LL403");
        // Nobody can deposit into, or withdraw from, the settlement account directly.
        assertThat(sqlStateOf(appJdbc, "select * from ledger_deposit(?, ?, ?, ?, ?)",
                owner.id(), UUID.fromString(SETTLEMENT), 100L, null, "steal")).isEqualTo("LL403");
        assertThat(sqlStateOf(appJdbc, withdraw, owner.id(), UUID.fromString(SETTLEMENT), 100L, null, "steal")).isEqualTo("LL403");

        for (LedgerApi.TestUser user : List.of(otherHolder, admin, auditor, disabled, owner)) {
            assertThat(keysFor(user.id(), "steal")).isZero();
        }
        assertThat(api.balance(account)).isEqualTo(1000);
        assertThat(api.balance(disabledAccount)).isEqualTo(1000);
    }

    // ---- V6 hardening (L5, L6, L2) -----------------------------------------------------------------------------------

    @Test
    void twoPostingsInOneCallerTransactionWorkAndTheCallersSettingsAreRestored() throws Exception {
        LedgerApi.TestUser holder = api.user("ACCOUNT_HOLDER");
        UUID account = api.fundedAccount(holder, "10");
        try (java.sql.Connection connection = appJdbc.getDataSource().getConnection()) {
            connection.setAutoCommit(false);
            try (java.sql.Statement statement = connection.createStatement()) {
                statement.execute("set local lock_timeout = '7s'");
            }
            for (int i = 0; i < 2; i++) {
                try (java.sql.PreparedStatement call = connection.prepareStatement(
                        "select r_outcome from ledger_deposit(?, ?, ?, ?, ?)")) {
                    call.setObject(1, holder.id());
                    call.setObject(2, account);
                    call.setLong(3, 100);
                    call.setObject(4, null);
                    call.setString(5, "tx-" + UUID.randomUUID());
                    try (java.sql.ResultSet rs = call.executeQuery()) {
                        rs.next();
                        assertThat(rs.getString(1)).isEqualTo("POSTED");
                    }
                }
            }
            try (java.sql.Statement statement = connection.createStatement();
                 java.sql.ResultSet rs = statement.executeQuery("select current_setting('lock_timeout'), "
                         + "current_setting('idle_in_transaction_session_timeout')")) {
                rs.next();
                assertThat(rs.getString(1)).as("caller's lock_timeout restored (L5)").isEqualTo("7s");
                assertThat(rs.getString(2)).as("an idle posting transaction is ended after 30 s (L6)").isEqualTo("30s");
            }
            connection.commit();
        }
        assertThat(api.balance(account)).isEqualTo(1200);
        assertThat(appJdbc.queryForList("select check_name from ledger_reconciliation()", String.class)).isEmpty();
    }

    @Test
    void aRoleChangeCommittedWhileAPostingWaitsIsSeenByThatPosting() throws Exception {
        LedgerApi.TestUser holder = api.user("ACCOUNT_HOLDER");
        UUID account = api.fundedAccount(holder, "10");
        try (java.sql.Connection roleChange = ownerJdbc.getDataSource().getConnection();
             java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            roleChange.setAutoCommit(false);
            // What set-user-role.sql does: lock the user row FOR UPDATE, then change the role.
            try (java.sql.PreparedStatement lock = roleChange.prepareStatement("select id from users where id = ? for update")) {
                lock.setObject(1, holder.id());
                lock.execute();
            }
            try (java.sql.PreparedStatement update = roleChange.prepareStatement("update users set role = 'AUDITOR' where id = ?")) {
                update.setObject(1, holder.id());
                update.execute();
            }
            java.util.concurrent.Future<String> posting = pool.submit(() -> sqlStateOf(appJdbc,
                    "select * from ledger_withdraw(?, ?, ?, ?, ?)", holder.id(), account, 100L, null, "race-" + UUID.randomUUID()));
            Thread.sleep(1_000);                         // the posting is now waiting on the user row (FOR SHARE)
            assertThat(posting.isDone()).as("the posting waits for the role change").isFalse();
            roleChange.commit();
            assertThat(posting.get()).as("it then sees the new role and refuses").isEqualTo("LL403");
        }
        assertThat(api.balance(account)).isEqualTo(1000);
    }
}
