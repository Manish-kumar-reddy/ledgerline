package com.ledgerline.support;

import java.util.List;
import javax.sql.DataSource;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ContextConfigurationAttributes;
import org.springframework.test.context.ContextCustomizer;
import org.springframework.test.context.ContextCustomizerFactory;
import org.springframework.test.context.MergedContextConfiguration;

/**
 * Stops every Spring test that uses a database from touching anything except the local ledgerline_test_db.
 *
 * How it applies to every integration test: this factory is listed in src/test/resources/META-INF/spring.factories.
 * Spring's TestContext framework loads every ContextCustomizerFactory listed there and applies it to every test
 * application context it builds. No test class has to import or extend anything, so a new *IT cannot forget it.
 *
 * When it runs: the customizer adds a BeanFactoryPostProcessor. It runs after all bean definitions are known but
 * before any bean is created, so before Hikari, Flyway or Hibernate can open a connection. It only checks contexts
 * that define a DataSource or Flyway bean; web-only unit tests such as @WebMvcTest are left alone.
 */
public class TestDatabaseGuard implements ContextCustomizerFactory {

    /** Every Spring setting that can carry the JDBC URL used by the pool or by Flyway. */
    static final List<String> URL_PROPERTIES =
            List.of("spring.datasource.url", "spring.datasource.hikari.jdbc-url", "spring.flyway.url");

    private static final Log log = LogFactory.getLog(TestDatabaseGuard.class);

    @Override
    public ContextCustomizer createContextCustomizer(
            Class<?> testClass, List<ContextConfigurationAttributes> configAttributes) {
        return new GuardCustomizer();
    }

    /** Equal instances let Spring keep caching test contexts as before. */
    static final class GuardCustomizer implements ContextCustomizer {

        @Override
        public void customizeContext(ConfigurableApplicationContext context, MergedContextConfiguration mergedConfig) {
            context.addBeanFactoryPostProcessor(new DatabaseUrlCheck(context.getEnvironment()));
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof GuardCustomizer;
        }

        @Override
        public int hashCode() {
            return GuardCustomizer.class.hashCode();
        }
    }

    static final class DatabaseUrlCheck implements BeanFactoryPostProcessor, Ordered {

        private final Environment environment;

        DatabaseUrlCheck(Environment environment) {
            this.environment = environment;
        }

        @Override
        public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
            // Type checks only (no eager initialisation), so this creates no beans and opens no connections.
            boolean usesDatabase = beanFactory.getBeanNamesForType(DataSource.class, true, false).length > 0
                    || beanFactory.getBeanNamesForType(Flyway.class, true, false).length > 0;
            if (!usesDatabase) {
                return;
            }

            // spring.datasource.url is required. The other two are optional overrides, but if present they
            // would be used to connect, so they must pass the same check.
            TestDatabaseUrl.requireLocalTestDatabase("spring.datasource.url", resolve("spring.datasource.url"));
            for (String property : URL_PROPERTIES.subList(1, URL_PROPERTIES.size())) {
                if (environment.containsProperty(property)) {
                    TestDatabaseUrl.requireLocalTestDatabase(property, resolve(property));
                }
            }
            log.info("TestDatabaseGuard: database URLs verified as the local " + TestDatabaseUrl.DATABASE);
        }

        private String resolve(String property) {
            try {
                return environment.getProperty(property);
            } catch (IllegalArgumentException unresolvedPlaceholder) {
                return null;
            }
        }

        @Override
        public int getOrder() {
            return Ordered.HIGHEST_PRECEDENCE;
        }
    }
}
