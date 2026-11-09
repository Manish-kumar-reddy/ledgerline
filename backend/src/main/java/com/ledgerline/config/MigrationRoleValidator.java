package com.ledgerline.config;

import java.util.regex.Pattern;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.springframework.boot.flyway.autoconfigure.FlywayConfigurationCustomizer;
import org.springframework.stereotype.Component;

/**
 * Validates the {@code app_role} Flyway placeholder before any migration runs.
 *
 * Flyway pastes placeholders into the SQL as raw text before PostgreSQL parses it, so a value such as
 * {@code x'; DROP TABLE users; --} would become executable SQL running as the migration owner. SQL-side checks
 * cannot stop that, because the injected text breaks out of the quotes before they run. This check can: it
 * fails start-up unless the value is a plain lower-case PostgreSQL role name.
 */
@Component
public class MigrationRoleValidator implements FlywayConfigurationCustomizer {

    static final String PLACEHOLDER = "app_role";
    static final Pattern ROLE_NAME = Pattern.compile("[a-z_][a-z0-9_]{0,62}");

    @Override
    public void customize(FluentConfiguration configuration) {
        validate(configuration.getPlaceholders().get(PLACEHOLDER), configuration.getUser());
    }

    static void validate(String appRole, String migrationUser) {
        if (appRole == null || !ROLE_NAME.matcher(appRole).matches()) {
            throw new IllegalStateException("Flyway placeholder " + PLACEHOLDER
                    + " must be a lower-case PostgreSQL role name matching " + ROLE_NAME.pattern()
                    + ". Refusing to run migrations.");
        }
        if (appRole.equals(migrationUser)) {
            throw new IllegalStateException("The application role must differ from the Flyway migration user. "
                    + "Refusing to run migrations.");
        }
    }
}
