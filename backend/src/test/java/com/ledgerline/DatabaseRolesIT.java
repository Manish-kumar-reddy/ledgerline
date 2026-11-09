package com.ledgerline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.ledgerline.support.TestDatabaseUrl;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Proves, with real queries, that the application and the migrations use different PostgreSQL roles,
 * and that the application role cannot change the schema. Runs against ledgerline_test_db only
 * (enforced automatically by TestDatabaseGuard).
 *
 * The expected role names come from the same settings the application uses, with no defaults: a missing
 * setting should fail the test, not be papered over with an assumed name.
 */
@SpringBootTest
class DatabaseRolesIT {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Flyway flyway;

    @Value("${spring.datasource.username}")
    private String runtimeRole;

    @Value("${spring.flyway.user}")
    private String migrationRole;

    @Test
    void runtimeAndMigrationRolesAreDifferent() {
        assertThat(runtimeRole).isNotBlank();
        assertThat(migrationRole).isNotBlank();
        assertThat(runtimeRole).isNotEqualTo(migrationRole);
    }

    @Test
    void applicationConnectsAsTheRuntimeRoleToTheTestDatabase() {
        assertThat(jdbc.queryForObject("select current_user", String.class)).isEqualTo(runtimeRole);
        assertThat(jdbc.queryForObject("select current_database()", String.class)).isEqualTo(TestDatabaseUrl.DATABASE);
    }

    @Test
    void migrationsWereAppliedByTheMigrationRole() {
        MigrationInfo[] applied = flyway.info().applied();

        assertThat(applied).isNotEmpty();
        assertThat(applied).allSatisfy(migration -> assertThat(migration.getInstalledBy()).isEqualTo(migrationRole));
        assertThat(applied).extracting(migration -> migration.getVersion().getVersion())
                .containsExactly("1", "2", "3", "4", "5", "6");
    }

    @Test
    void runtimeRoleCanUseButNotCreateInThePublicSchema() {
        assertThat(jdbc.queryForObject("select has_schema_privilege(current_user, 'public', 'USAGE')", Boolean.class))
                .isTrue();
        assertThat(jdbc.queryForObject("select has_schema_privilege(current_user, 'public', 'CREATE')", Boolean.class))
                .isFalse();
    }

    @Test
    void runtimeRoleIsRefusedWhenItTriesToCreateATable() {
        BadSqlGrammarException refused = catchThrowableOfType(
                BadSqlGrammarException.class, () -> jdbc.execute("create table should_not_exist (id int)"));

        // Spring's wrapper message omits PostgreSQL's text, so check the underlying SQLException.
        // SQLSTATE 42501 is PostgreSQL's "insufficient_privilege".
        assertThat(refused).isNotNull();
        assertThat(refused.getSQLException().getSQLState()).isEqualTo("42501");
        assertThat(refused.getSQLException().getMessage()).contains("permission denied for schema public");
    }
}
