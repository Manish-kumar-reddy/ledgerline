package com.ledgerline.dto;

import com.ledgerline.validation.RejectUnknownProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * The only thing a client chooses when opening an account is its name. Owner, id, currency and status are not
 * fields here, and unknown JSON properties are rejected ({@link RejectUnknownProperties}), so sending
 * {@code ownerId}, {@code status}, {@code currency}, {@code id} or {@code role} is a 400, not silently ignored.
 */
@RejectUnknownProperties
public record CreateAccountRequest(
        @NotBlank @Size(max = 100)
        @Pattern(regexp = "[^\\p{Cntrl}]*", message = "must not contain control characters")
        String name) {

    public CreateAccountRequest {
        name = name == null ? null : name.strip();
    }
}
