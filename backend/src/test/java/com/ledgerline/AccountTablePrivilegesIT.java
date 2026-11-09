package com.ledgerline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.ledgerline.support.OwnerJdbc;
import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Real SQL as the runtime role (ledgerline_app) against the accounts table from V4: PostgreSQL itself, not the
 * Java code, stops the application from changing an account's owner or status, or deleting accounts.
 */
@SpringBootTest
class AccountTablePrivilegesIT {

    private static final String HASH = "$2a$12$" + "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0";

    @Autowired
    private JdbcTemplate appJdbc;

    @Autowired
    private Environment environment;

    private JdbcTemplate ownerJdbc;

    @BeforeEach
    void connectAsOwner() {
        ownerJdbc = OwnerJdbc.forTestDatabase(environment);
    }

    private static String sqlStateOf(JdbcTemplate jdbc, String sql, Object... args) {
        DataAccessException failure = catchThrowableOfType(DataAccessException.class, () -> jdbc.update(sql, args));
        assertThat(failure).as("expected PostgreSQL to refuse: %s", sql).isNotNull();
        Throwable cause = failure.getMostSpecificCause();
        assertThat(cause).isInstanceOf(SQLException.class);
        return ((SQLException) cause).getSQLState();
    }

    private UUID newUser() {
        String email = "acct-priv-" + UUID.randomUUID() + "@example.test";
        appJdbc.update("insert into users (full_name, email, password_hash) values ('P', ?, ?)", email, HASH);
        return appJdbc.queryForObject("select id from users where email = ?", UUID.class, email);
    }

    private UUID newAccount(UUID owner) {
        UUID id = UUID.randomUUID();
        appJdbc.update("insert into accounts (id, owner_id, name) values (?, ?, 'Priv')", id, owner);
        return id;
    }

    @Test
    void anInsertWithOnlyTheAllowedColumnsGetsINRAndACTIVE() {
        UUID account = newAccount(newUser());
        Map<String, Object> row = appJdbc.queryForMap("select currency, status, created_at from accounts where id = ?", account);
        assertThat(row.get("currency")).isEqualTo("INR");
        assertThat(row.get("status")).isEqualTo("ACTIVE");
        assertThat(row.get("created_at")).isNotNull();
    }

    @Test
    void cannotInsertCurrencyStatusOrTimestamp() {
        UUID owner = newUser();
        // 42501 = insufficient_privilege
        assertThat(sqlStateOf(appJdbc, "insert into accounts (owner_id, name, currency) values (?, 'X', 'INR')", owner)).isEqualTo("42501");
        assertThat(sqlStateOf(appJdbc, "insert into accounts (owner_id, name, status) values (?, 'X', 'ACTIVE')", owner)).isEqualTo("42501");
        assertThat(sqlStateOf(appJdbc, "insert into accounts (owner_id, name, created_at) values (?, 'X', now())", owner)).isEqualTo("42501");
    }

    @Test
    void cannotChangeOwnerStatusOrAnythingElse() {
        UUID owner = newUser();
        UUID other = newUser();
        UUID account = newAccount(owner);
        assertThat(sqlStateOf(appJdbc, "update accounts set owner_id = ? where id = ?", other, account)).isEqualTo("42501");
        assertThat(sqlStateOf(appJdbc, "update accounts set status = 'FROZEN' where id = ?", account)).isEqualTo("42501");
        assertThat(sqlStateOf(appJdbc, "update accounts set name = 'renamed' where id = ?", account)).isEqualTo("42501");
        assertThat(sqlStateOf(appJdbc, "update accounts set currency = 'INR' where id = ?", account)).isEqualTo("42501");
        assertThat(appJdbc.queryForObject("select owner_id from accounts where id = ?", UUID.class, account)).isEqualTo(owner);
    }

    @Test
    void cannotDeleteTruncateAlterOrDrop() {
        UUID account = newAccount(newUser());
        assertThat(sqlStateOf(appJdbc, "delete from accounts where id = ?", account)).isEqualTo("42501");
        assertThat(sqlStateOf(appJdbc, "truncate accounts")).isEqualTo("42501");
        assertThat(sqlStateOf(appJdbc, "alter table accounts add column x int")).isEqualTo("42501");
        assertThat(sqlStateOf(appJdbc, "drop table accounts")).isEqualTo("42501");
        assertThat(appJdbc.queryForObject("select count(*) from accounts where id = ?", Integer.class, account)).isEqualTo(1);
    }

    @Test
    void databaseEnforcesOwnerExistenceAndAName() {
        // 23503 = foreign_key_violation, 23514 = check_violation
        assertThat(sqlStateOf(appJdbc, "insert into accounts (owner_id, name) values (?, 'X')", UUID.randomUUID())).isEqualTo("23503");
        assertThat(sqlStateOf(appJdbc, "insert into accounts (owner_id, name) values (?, '   ')", newUser())).isEqualTo("23514");
    }

    @Test
    void evenTheOwnerRoleCannotStoreAnotherCurrencyOrAnUnknownStatus() {
        UUID account = newAccount(newUser());
        assertThat(sqlStateOf(ownerJdbc, "update accounts set currency = 'USD' where id = ?", account)).isEqualTo("23514");
        assertThat(sqlStateOf(ownerJdbc, "update accounts set status = 'BOGUS' where id = ?", account)).isEqualTo("23514");
    }

    @Test
    void privilegeCatalogMatchesTheDesign() {
        assertThat(appJdbc.queryForList("""
                select privilege_type || ':' || string_agg(column_name, ',' order by column_name)
                from information_schema.column_privileges
                where table_name = 'accounts' and grantee = current_user
                group by privilege_type order by privilege_type""", String.class))
                .containsExactly("INSERT:id,name,owner_id", "SELECT:created_at,currency,id,name,owner_id,status");
        for (String privilege : new String[] {"UPDATE", "DELETE", "TRUNCATE", "REFERENCES", "TRIGGER"}) {
            assertThat(appJdbc.queryForObject("select has_table_privilege(current_user, 'accounts', ?)", Boolean.class, privilege))
                    .as(privilege).isFalse();
        }
        // PUBLIC (grantee OID 0) has no privileges at all on the table.
        assertThat(appJdbc.queryForObject("""
                select count(*) from pg_class c cross join lateral aclexplode(c.relacl) a
                where c.relname = 'accounts' and a.grantee = 0""", Integer.class)).isZero();
        assertThat(appJdbc.queryForObject("select tableowner from pg_tables where tablename = 'accounts'", String.class))
                .isNotEqualTo(appJdbc.queryForObject("select current_user", String.class));
    }
}
