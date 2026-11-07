package com.ledgerline.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zaxxer.hikari.HikariDataSource;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.core.io.support.SpringFactoriesLoader;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.context.ContextCustomizerFactory;

/**
 * Unit test (no database). The bean factories below hold only bean definitions: no DataSource or Flyway object
 * is ever created, so if the check throws, it has done so before anything could connect.
 */
class TestDatabaseGuardTest {

    private static final String TEST_URL = "jdbc:postgresql://localhost:5432/ledgerline_test_db";

    private static DefaultListableBeanFactory factoryWith(Class<?> beanClass) {
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        factory.registerBeanDefinition("bean", new RootBeanDefinition(beanClass));
        return factory;
    }

    private static void check(MockEnvironment environment, DefaultListableBeanFactory factory) {
        new TestDatabaseGuard.DatabaseUrlCheck(environment).postProcessBeanFactory(factory);
    }

    @Test
    void isRegisteredForEverySpringTestThroughSpringFactories() {
        List<ContextCustomizerFactory> factories = SpringFactoriesLoader.forDefaultResourceLocation()
                .load(ContextCustomizerFactory.class);

        assertThat(factories).hasAtLeastOneElementOfType(TestDatabaseGuard.class);
    }

    @Test
    void allowsTheLocalTestDatabase() {
        MockEnvironment environment = new MockEnvironment().withProperty("spring.datasource.url", TEST_URL);

        assertThatCode(() -> check(environment, factoryWith(HikariDataSource.class))).doesNotThrowAnyException();
    }

    @Test
    void refusesTheDevelopmentDatabaseBeforeTheDataSourceIsCreated() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.datasource.url", "jdbc:postgresql://localhost:5432/ledgerline_db");
        DefaultListableBeanFactory factory = factoryWith(HikariDataSource.class);

        assertThatThrownBy(() -> check(environment, factory))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("names database 'ledgerline_db'");
        assertThat(factory.getSingletonCount()).as("no bean was created").isZero();
    }

    @Test
    void alsoGuardsContextsThatOnlyDefineFlyway() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.datasource.url", "jdbc:postgresql://db.example.com:5432/ledgerline_test_db");

        assertThatThrownBy(() -> check(environment, factoryWith(Flyway.class)))
                .hasMessageContaining("points at host 'db.example.com'");
    }

    @Test
    void refusesAMissingUrlWhenADataSourceExists() {
        assertThatThrownBy(() -> check(new MockEnvironment(), factoryWith(HikariDataSource.class)))
                .hasMessageContaining("spring.datasource.url is missing");
    }

    @Test
    void refusesAnUnresolvablePlaceholder() {
        MockEnvironment environment = new MockEnvironment().withProperty("spring.datasource.url", "${NOT_SET_ANYWHERE}");

        assertThatThrownBy(() -> check(environment, factoryWith(HikariDataSource.class)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void refusesAFlywayUrlOverride() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.datasource.url", TEST_URL)
                .withProperty("spring.flyway.url", "jdbc:postgresql://localhost:5432/ledgerline_db");

        assertThatThrownBy(() -> check(environment, factoryWith(HikariDataSource.class)))
                .hasMessageContaining("spring.flyway.url names database 'ledgerline_db'");
    }

    @Test
    void refusesAHikariUrlOverride() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.datasource.url", TEST_URL)
                .withProperty("spring.datasource.hikari.jdbc-url", "jdbc:postgresql://localhost:5433/ledgerline_test_db");

        assertThatThrownBy(() -> check(environment, factoryWith(HikariDataSource.class)))
                .hasMessageContaining("spring.datasource.hikari.jdbc-url uses port 5433");
    }

    @Test
    void ignoresContextsWithoutADatabase() {
        // Like @WebMvcTest: no DataSource or Flyway bean, so even the development URL is not checked.
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.datasource.url", "jdbc:postgresql://localhost:5432/ledgerline_db");

        assertThatCode(() -> check(environment, factoryWith(String.class))).doesNotThrowAnyException();
    }
}
