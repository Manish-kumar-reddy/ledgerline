package com.ledgerline.dto;

import com.ledgerline.money.JsonStringOnly;
import com.ledgerline.money.Money;
import com.ledgerline.validation.RejectUnknownProperties;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.annotation.JsonDeserialize;

/**
 * Body of a deposit or withdrawal. {@code amount} is rupees as a JSON string ("1500.50"); a JSON number is rejected.
 * There is no account, user or initiator field: the account comes from the path and the initiator from the
 * authenticated user. Unknown properties are rejected.
 */
@RejectUnknownProperties
public record MoneyMovementRequest(
        @NotNull @JsonDeserialize(using = JsonStringOnly.class)
        @Pattern(regexp = Money.AMOUNT_REGEX, message = AMOUNT_MESSAGE)
        String amount,
        @Size(max = 140) @Pattern(regexp = "[^\\p{Cntrl}]*", message = "must not contain control characters")
        String description) {

    public static final String AMOUNT_MESSAGE =
            "must be a positive amount in rupees as a JSON string, with at most 2 decimal places";

    public MoneyMovementRequest {
        description = description == null || description.isBlank() ? null : description.strip();
    }
}
