package com.ledgerline.dto;

import com.ledgerline.entity.Account;
import com.ledgerline.entity.AccountStatus;
import java.time.Instant;
import java.util.UUID;

/** An account as returned by the API. Only the owner's id is included: no name, email or other user data. */
public record AccountResponse(UUID id, UUID ownerId, String name, String currency, AccountStatus status, Instant createdAt) {

    public static AccountResponse from(Account account) {
        return new AccountResponse(account.getId(), account.getOwnerId(), account.getName(), account.getCurrency(),
                account.getStatus(), account.getCreatedAt());
    }
}
