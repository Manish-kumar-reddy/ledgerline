package com.ledgerline.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ledgerline.controller.HealthController;
import com.ledgerline.dto.LoginRequest;
import com.ledgerline.dto.RegisterRequest;
import com.ledgerline.repository.UserRepository;
import com.ledgerline.support.WebSecurityTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.exc.UnrecognizedPropertyException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Uses the application's own JsonMapper (the one Spring MVC uses), configured exactly as Spring Boot configures
 * it, to show that strict handling applies to the auth requests and nowhere else. No database.
 */
@WebMvcTest(HealthController.class)
@WebSecurityTest
class StrictJsonModuleTest {

    /** Stands in for any future request type that does not opt in. */
    record OrdinaryRequest(String name) {
    }

    @Autowired
    private JsonMapper json;

    @MockitoBean
    private UserRepository users;

    @Test
    void registrationWithAnUnknownPropertyIsRejected() {
        assertThatThrownBy(() -> json.readValue(
                "{\"fullName\":\"A\",\"email\":\"a@example.com\",\"password\":\"x\",\"role\":\"ADMIN\"}", RegisterRequest.class))
                .isInstanceOf(UnrecognizedPropertyException.class)
                .hasMessageContaining("\"role\"");
    }

    @Test
    void loginWithAnUnknownPropertyIsRejected() {
        assertThatThrownBy(() -> json.readValue("{\"email\":\"a@example.com\",\"password\":\"x\",\"rememberMe\":true}", LoginRequest.class))
                .isInstanceOf(UnrecognizedPropertyException.class);
    }

    @Test
    void authRequestsWithoutUnknownPropertiesStillWork() throws Exception {
        assertThat(json.readValue("{\"email\":\"A@Example.com\",\"password\":\"x\"}", LoginRequest.class).email())
                .isEqualTo("a@example.com");
    }

    @Test
    void otherTypesKeepTheDefaultAndIgnoreUnknownProperties() throws Exception {
        OrdinaryRequest parsed = json.readValue("{\"name\":\"n\",\"somethingNew\":42,\"role\":\"ADMIN\"}", OrdinaryRequest.class);
        assertThat(parsed.name()).isEqualTo("n");
    }
}
