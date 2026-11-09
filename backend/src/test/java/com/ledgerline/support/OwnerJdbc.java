package com.ledgerline.support;

import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * A connection as the migration owner, for integration tests that must do what the application itself cannot:
 * disable a user or change a user's role.
 *
 * It reuses spring.datasource.url, which TestDatabaseGuard has already verified for this context, and checks
 * it again here before connecting, because a plain JDBC connection is not covered by the Spring-context guard.
 */
public final class OwnerJdbc {

    private OwnerJdbc() {
    }

    public static JdbcTemplate forTestDatabase(Environment environment) {
        String url = environment.getRequiredProperty("spring.datasource.url");
        TestDatabaseUrl.requireLocalTestDatabase("spring.datasource.url", url);
        return new JdbcTemplate(new DriverManagerDataSource(url,
                environment.getRequiredProperty("spring.flyway.user"),
                environment.getRequiredProperty("spring.flyway.password")));
    }
}
