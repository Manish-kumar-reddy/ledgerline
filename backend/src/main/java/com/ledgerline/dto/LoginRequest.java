package com.ledgerline.dto;

import com.ledgerline.validation.Emails;
import com.ledgerline.validation.RejectUnknownProperties;
import com.ledgerline.validation.Utf8ByteLength;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Login input. Only shape is validated here (no password policy), so validation itself says nothing about
 * whether an account exists. The 72-byte cap is needed because Spring's BCrypt refuses longer passwords.
 */
@RejectUnknownProperties
public record LoginRequest(
        @NotBlank @Size(max = 254)
        String email,

        @NotNull @Utf8ByteLength(min = 1, max = 72)
        String password) {

    public LoginRequest {
        email = Emails.normalise(email);
    }

    @Override
    public String toString() {
        return "LoginRequest[email=" + email + ", password=<hidden>]";
    }
}
