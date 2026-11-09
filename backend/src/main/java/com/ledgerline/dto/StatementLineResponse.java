package com.ledgerline.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * One statement line. POSTED lines are ledger entries (DEBIT or CREDIT); REFUSED lines are refused attempts, which
 * have no direction and leave the running balance unchanged. {@code balance} is the running balance after the line.
 */
public record StatementLineResponse(long sequence, String status, String operation, String direction, String amount,
        long amountMinor, String balance, long balanceMinor, String reason, UUID initiatedBy,
        UUID counterpartyAccountId, UUID transactionId, UUID refusedAttemptId, String description, Instant occurredAt) {
}
