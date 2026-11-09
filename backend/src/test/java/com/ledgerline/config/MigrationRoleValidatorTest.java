package com.ledgerline.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/** Unit test (no database). */
class MigrationRoleValidatorTest {

    private static FluentConfiguration configuration(String appRole, String user) {
        FluentConfiguration configuration = new FluentConfiguration().dataSource("jdbc:postgresql://unused/none", user, "unused");
        return appRole == null ? configuration : configuration.placeholders(Map.of("app_role", appRole));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ledgerline_app", "app", "_role1", "a23456789012345678901234567890123456789012345678901234567890123"})
    void acceptsPlainLowerCaseRoleNames(String role) {
        assertThatCode(() -> new MigrationRoleValidator().customize(configuration(role, "ledgerline_owner")))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
            "Ledgerline_App",
            "ledger-app",
            "1app",
            "app role",
            "x'; DROP TABLE users; --",
            "app\"",
            "a234567890123456789012345678901234567890123456789012345678901234"})
    void rejectsAnythingElseBeforeFlywayConnects(String role) {
        assertThatThrownBy(() -> new MigrationRoleValidator().customize(configuration(role, "ledgerline_owner")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Refusing to run migrations");
    }

    @Test
    void rejectsTheMigrationUserAsTheApplicationRole() {
        assertThatThrownBy(() -> new MigrationRoleValidator().customize(configuration("ledgerline_owner", "ledgerline_owner")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must differ");
    }
}
