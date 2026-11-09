package com.ledgerline.dto;

import com.ledgerline.validation.Emails;
import com.ledgerline.validation.RejectUnknownProperties;
import com.ledgerline.validation.Utf8ByteLength;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Registration input. There is deliberately no role or enabled field, and unknown JSON properties are rejected
 * ({@link RejectUnknownProperties}), so a client cannot even attempt to send them.
 *
 * Name and email are normalised here, before validation, so what is validated is exactly what is stored.
 * Emails are restricted to printable ASCII so Java's and PostgreSQL's lower-casing can never disagree.
 */
@RejectUnknownProperties
public record RegisterRequest(
        @NotBlank @Size(max = 100)
        String fullName,

        @NotBlank @Size(max = 254) @Email
        @Pattern(regexp = "[\\x21-\\x7E]+", message = "must contain only printable ASCII characters")
        String email,

        @NotNull @Utf8ByteLength(min = 12, max = 72)
        String password) {

    public RegisterRequest {
        fullName = fullName == null ? null : fullName.strip();
        email = Emails.normalise(email);
    }

    /** Never print the password, e.g. in logs or exception messages. */
    @Override
    public String toString() {
        return "RegisterRequest[fullName=" + fullName + ", email=" + email + ", password=<hidden>]";
    }
}
