package com.ledgerline.dto;

import com.ledgerline.money.Money;
import com.ledgerline.service.PostingResult;
import java.time.Instant;
import java.util.UUID;

/**
 * Result of a deposit, withdrawal or transfer: POSTED (201) or REFUSED (422, with a reason). A replay of the same
 * idempotency key returns the same body (plus the Idempotent-Replayed header).
 * {@code balance} is the caller's account balance after the posting, or unchanged at the time of the refusal.
 */
public record PostingResponse(String outcome, String operation, UUID transactionId, UUID refusedAttemptId,
        String reason, UUID accountId, UUID counterpartyAccountId, String amount, long amountMinor, String balance,
        long balanceMinor, long sequence, UUID initiatedBy, Instant occurredAt) {

    public static PostingResponse from(PostingResult result) {
        return new PostingResponse(result.outcome(), result.kind(), result.journalId(), result.refusedId(),
                result.reason(), result.accountId(), result.counterpartyAccountId(), Money.format(result.amountMinor()),
                result.amountMinor(), Money.format(result.balanceMinor()), result.balanceMinor(), result.seq(),
                result.initiatedBy(), result.occurredAt());
    }
}
