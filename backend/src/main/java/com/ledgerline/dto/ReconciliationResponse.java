package com.ledgerline.dto;

import java.util.List;
import java.util.UUID;

/**
 * Independent recomputation of the books from the raw ledger rows. {@code consistent} is true only when no
 * difference of any kind was found. At most 100 differences are listed; {@code differenceCount} is the total.
 */
public record ReconciliationResponse(boolean consistent, long differenceCount, List<Difference> differences,
        long accounts, long journalTransactions, long refusedAttempts, String customerBalanceTotal,
        long customerBalanceTotalMinor, String settlementBalance, long settlementBalanceMinor, long totalBalanceMinor) {

    public record Difference(String check, UUID subjectId, long expected, long actual) {
    }
}
