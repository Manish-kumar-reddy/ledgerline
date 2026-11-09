package com.ledgerline.service;

import java.time.Instant;
import java.util.UUID;

/** One row returned by ledger_deposit / ledger_withdraw / ledger_transfer (see V5). */
public record PostingResult(String outcome, boolean replayed, String kind, UUID journalId, UUID refusedId,
        String reason, UUID accountId, UUID counterpartyAccountId, long amountMinor, long balanceMinor, long seq,
        Instant occurredAt, UUID initiatedBy) {

    public boolean posted() {
        return "POSTED".equals(outcome);
    }
}
