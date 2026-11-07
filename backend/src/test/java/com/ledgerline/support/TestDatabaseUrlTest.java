package com.ledgerline.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/** Unit test (no database): the URL rules on their own. */
class TestDatabaseUrlTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "jdbc:postgresql://localhost:5432/ledgerline_test_db",
            "jdbc:postgresql://127.0.0.1:5432/ledgerline_test_db",
            "jdbc:postgresql://[::1]:5432/ledgerline_test_db",
            "jdbc:postgresql://LOCALHOST:5432/ledgerline_test_db",
            "jdbc:postgresql://localhost/ledgerline_test_db",
            "jdbc:postgresql://localhost:5432/ledgerline_test_db?sslmode=disable&ApplicationName=it"})
    void acceptsTheLocalTestDatabase(String url) {
        assertThat(TestDatabaseUrl.problemWith(url)).isNull();
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(delimiter = '|', value = {
            // wrong database
            "jdbc:postgresql://localhost:5432/ledgerline_db                         | names database 'ledgerline_db'",
            "jdbc:postgresql://localhost:5432/ledgerline_test_db_copy               | names database",
            "jdbc:postgresql://localhost:5432/ledgerline%5Ftest_db                  | names database",
            "jdbc:postgresql://localhost:5432/                                      | names database ''",
            // unexpected host
            "jdbc:postgresql://db.example.com:5432/ledgerline_test_db               | points at host 'db.example.com'",
            "jdbc:postgresql://10.0.0.5:5432/ledgerline_test_db                     | points at host '10.0.0.5'",
            "jdbc:postgresql:///ledgerline_test_db                                  | points at host ''",
            // unexpected port
            "jdbc:postgresql://localhost:5433/ledgerline_test_db                    | uses port 5433",
            "jdbc:postgresql://localhost:abc/ledgerline_test_db                     | has a malformed port",
            // multi-host and user information
            "jdbc:postgresql://localhost:5432,prod.example.com:5432/ledgerline_test_db | lists more than one host",
            "jdbc:postgresql://user@localhost:5432/ledgerline_test_db               | contains user information",
            // query parameters that could redirect the connection
            "jdbc:postgresql://localhost:5432/ledgerline_test_db?PGDBNAME=ledgerline_db | query parameter 'PGDBNAME'",
            "jdbc:postgresql://localhost:5432/ledgerline_test_db?PGHOST=prod.example.com | query parameter 'PGHOST'",
            "jdbc:postgresql://localhost:5432/ledgerline_test_db?sslmode=require&service=prod | query parameter 'service'",
            // malformed
            "jdbc:postgresql:ledgerline_test_db                                     | is not a jdbc:postgresql://",
            "jdbc:mysql://localhost:5432/ledgerline_test_db                         | is not a jdbc:postgresql://",
            "postgres://localhost:5432/ledgerline_test_db                           | is not a jdbc:postgresql://",
            "jdbc:postgresql://localhost:5432                                       | has no database name",
            "jdbc:postgresql://[::1:5432/ledgerline_test_db                         | malformed IPv6 host"})
    void rejectsAnythingElse(String url, String reason) {
        assertThat(TestDatabaseUrl.problemWith(url)).isNotNull().contains(reason);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = "   ")
    void rejectsAMissingUrl(String url) {
        assertThat(TestDatabaseUrl.problemWith(url)).isEqualTo("is missing");
    }

    @org.junit.jupiter.api.Test
    void theErrorNamesTheSettingAndSaysNoConnectionWasOpened() {
        assertThatThrownBy(() -> TestDatabaseUrl.requireLocalTestDatabase(
                        "spring.datasource.url", "jdbc:postgresql://localhost:5432/ledgerline_db"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("spring.datasource.url names database 'ledgerline_db'")
                .hasMessageContaining("No database connection was opened");
    }
}
