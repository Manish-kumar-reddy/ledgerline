package com.ledgerline.validation;

import static org.assertj.core.api.Assertions.assertThat;

import com.ledgerline.dto.RegisterRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Unit test (no database): the password rule is 12 to 72 UTF-8 bytes. */
class Utf8ByteLengthValidatorTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    private Set<ConstraintViolation<RegisterRequest>> violationsFor(String password) {
        return validator.validate(new RegisterRequest("Asha Rao", "asha@example.com", password));
    }

    @Test
    void acceptsTheBoundaries() {
        assertThat(violationsFor("a".repeat(12))).isEmpty();
        assertThat(violationsFor("a".repeat(72))).isEmpty();
    }

    @Test
    void rejectsJustOutsideTheBoundaries() {
        assertThat(violationsFor("a".repeat(11))).extracting(v -> v.getPropertyPath().toString()).containsExactly("password");
        assertThat(violationsFor("a".repeat(73))).extracting(v -> v.getPropertyPath().toString()).containsExactly("password");
    }

    @Test
    void countsBytesNotCharacters() {
        // "é" is 2 bytes in UTF-8: 36 of them are 72 bytes (allowed), 37 are 74 bytes (rejected),
        // even though 37 characters is well under 72 characters.
        assertThat(violationsFor("é".repeat(36))).isEmpty();
        assertThat(violationsFor("é".repeat(37))).isNotEmpty();
        // An emoji is 4 bytes: 3 of them are only 12 bytes, so this passes the minimum with just 3 characters.
        assertThat(violationsFor("😀😀😀")).isEmpty();
    }

    @Test
    void registrationNormalisesEmailAndNameBeforeValidation() {
        RegisterRequest request = new RegisterRequest("  Asha Rao  ", "  Asha@Example.COM ", "a".repeat(12));
        assertThat(request.email()).isEqualTo("asha@example.com");
        assertThat(request.fullName()).isEqualTo("Asha Rao");
        assertThat(validator.validate(request)).isEmpty();
    }

    @Test
    void rejectsNonAsciiEmails() {
        assertThat(validator.validate(new RegisterRequest("Asha", "ásha@example.com", "a".repeat(12))))
                .extracting(v -> v.getPropertyPath().toString()).contains("email");
    }

    @Test
    void requestToStringNeverShowsThePassword() {
        String password = "correct horse battery staple";
        assertThat(new RegisterRequest("Asha", "asha@example.com", password).toString()).doesNotContain(password);
        assertThat(new com.ledgerline.dto.LoginRequest("asha@example.com", password).toString()).doesNotContain(password);
    }
}
