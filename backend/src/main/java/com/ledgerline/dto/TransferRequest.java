package com.ledgerline.dto;

import com.ledgerline.money.JsonStringOnly;
import com.ledgerline.money.Money;
import com.ledgerline.validation.RejectUnknownProperties;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import tools.jackson.databind.annotation.JsonDeserialize;

/** Body of a transfer from the account in the path to {@code toAccountId}. Same amount rules as a deposit. */
@RejectUnknownProperties
public record TransferRequest(
        @NotNull UUID toAccountId,
        @NotNull @JsonDeserialize(using = JsonStringOnly.class)
        @Pattern(regexp = Money.AMOUNT_REGEX, message = MoneyMovementRequest.AMOUNT_MESSAGE)
        String amount,
        @Size(max = 140) @Pattern(regexp = "[^\\p{Cntrl}]*", message = "must not contain control characters")
        String description) {

    public TransferRequest {
        description = description == null || description.isBlank() ? null : description.strip();
    }
}
