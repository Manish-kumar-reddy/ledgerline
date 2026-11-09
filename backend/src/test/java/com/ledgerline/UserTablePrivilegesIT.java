package com.ledgerline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Real SQL attempts as the application's runtime role (ledgerline_app) against the users table. These prove the
 * restrictions are enforced by PostgreSQL itself, not just by the Java code.
 */
@SpringBootTest
class UserTablePrivilegesIT {

    /** Well-formed BCrypt-shaped value; not the hash of any password. */
    private static final String HASH = "$2a$12$" + "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0";

    @Autowired
    private JdbcTemplate appJdbc;

    private static String uniqueEmail() {
        return "priv-" + UUID.randomUUID() + "@example.test";
    }

    private String sqlStateOf(String sql, Object... args) {
        DataAccessException failure = catchThrowableOfType(DataAccessException.class, () -> appJdbc.update(sql, args));
        assertThat(failure).as("expected PostgreSQL to refuse: %s", sql).isNotNull();
        Throwable cause = failure.getMostSpecificCause();
        assertThat(cause).isInstanceOf(SQLException.class);
        return ((SQLException) cause).getSQLState();
    }

    private String insertUser() {
        String email = uniqueEmail();
        appJdbc.update("insert into users (full_name, email, password_hash) values (?, ?, ?)", "Priv Tester", email, HASH);
        return email;
    }

    @Test
    void runsAsTheRuntimeRole() {
        assertThat(appJdbc.queryForObject("select current_user", String.class)).isEqualTo("ledgerline_app");
    }

    @Test
    void anInsertWithOnlyTheAllowedColumnsGetsDatabaseDefaults() {
        String email = insertUser();
        Map<String, Object> row = appJdbc.queryForMap("select role, enabled from users where email = ?", email);
        assertThat(row).containsEntry("role", "ACCOUNT_HOLDER").containsEntry("enabled", true);
    }

    @Test
    void cannotInsertARoleEnabledFlagOrTimestamp() {
        assertThat(sqlStateOf("insert into users (full_name, email, password_hash, role) values ('M', ?, ?, 'ADMIN')", uniqueEmail(), HASH))
                .isEqualTo("42501");
        assertThat(sqlStateOf("insert into users (full_name, email, password_hash, enabled) values ('M', ?, ?, false)", uniqueEmail(), HASH))
                .isEqualTo("42501");
        assertThat(sqlStateOf("insert into users (full_name, email, password_hash, created_at) values ('M', ?, ?, now())", uniqueEmail(), HASH))
                .isEqualTo("42501");
    }

    @Test
    void cannotChangeRoleEnabledOrAnyOtherColumn() {
        String email = insertUser();
        assertThat(sqlStateOf("update users set role = 'ADMIN' where email = ?", email)).isEqualTo("42501");
        assertThat(sqlStateOf("update users set enabled = false where email = ?", email)).isEqualTo("42501");
        assertThat(sqlStateOf("update users set full_name = 'x' where email = ?", email)).isEqualTo("42501");
        assertThat(sqlStateOf("update users set password_hash = ? where email = ?", HASH, email)).isEqualTo("42501");
        assertThat(appJdbc.queryForObject("select role from users where email = ?", String.class, email)).isEqualTo("ACCOUNT_HOLDER");
    }

    @Test
    void cannotDeleteOrTruncateUsers() {
        String email = insertUser();
        assertThat(sqlStateOf("delete from users where email = ?", email)).isEqualTo("42501");
        assertThat(sqlStateOf("truncate users")).isEqualTo("42501");
        assertThat(appJdbc.queryForObject("select count(*) from users where email = ?", Integer.class, email)).isEqualTo(1);
    }

    @Test
    void cannotAlterOrDropTheTable() {
        assertThat(sqlStateOf("alter table users disable trigger all")).isEqualTo("42501");
        assertThat(sqlStateOf("drop table users")).isEqualTo("42501");
    }

    @Test
    void databaseRejectsPlaintextPasswordsAndUnnormalisedEmails() {
        // 23514 = check_violation
        assertThat(sqlStateOf("insert into users (full_name, email, password_hash) values ('P', ?, 'correct horse battery')", uniqueEmail()))
                .isEqualTo("23514");
        assertThat(sqlStateOf("insert into users (full_name, email, password_hash) values ('P', ?, ?)", "Mixed-" + uniqueEmail(), HASH))
                .isEqualTo("23514");
    }

    @Test
    void databaseRejectsDuplicateEmails() {
        String email = insertUser();
        // 23505 = unique_violation
        assertThat(sqlStateOf("insert into users (full_name, email, password_hash) values ('D', ?, ?)", email, HASH)).isEqualTo("23505");
    }

    @Test
    void privilegeCatalogMatchesTheDesign() {
        assertThat(appJdbc.queryForList("""
                select privilege_type || ':' || string_agg(column_name, ',' order by column_name)
                from information_schema.column_privileges
                where table_name = 'users' and grantee = current_user
                group by privilege_type order by privilege_type""", String.class))
                .containsExactly("INSERT:email,full_name,id,password_hash",
                        "SELECT:created_at,email,enabled,full_name,id,password_hash,role");
        for (String privilege : new String[] {"UPDATE", "DELETE", "TRUNCATE", "REFERENCES", "TRIGGER"}) {
            assertThat(appJdbc.queryForObject("select has_table_privilege(current_user, 'users', ?)", Boolean.class, privilege))
                    .as(privilege).isFalse();
        }
    }
}
