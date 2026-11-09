package com.ledgerline.dto;

import java.util.List;

/** A page of an account's statement, oldest line first, with the account (and its current balance). */
public record StatementResponse(AccountResponse account, List<StatementLineResponse> lines, int page, int size,
        long totalLines, int totalPages) {
}
