package com.ledgerline.dto;

import com.ledgerline.entity.Account;
import com.ledgerline.entity.AccountKind;
import com.ledgerline.entity.AccountStatus;
import com.ledgerline.money.Money;
import java.time.Instant;
import java.util.UUID;

/**
 * An account as returned by the API. Only the owner's id is included: no name, email or other user data.
 * The balance is the stored, ledger-backed balance; {@code balance} is rupees as a string, {@code balanceMinor} paise.
 */
public record AccountResponse(UUID id, UUID ownerId, AccountKind kind, String name, String currency,
        AccountStatus status, String balance, long balanceMinor, Instant createdAt) {

    public static AccountResponse from(Account account) {
        return new AccountResponse(account.getId(), account.getOwnerId(), account.getKind(), account.getName(),
                account.getCurrency(), account.getStatus(), Money.format(account.getBalanceMinor()),
                account.getBalanceMinor(), account.getCreatedAt());
    }
}
