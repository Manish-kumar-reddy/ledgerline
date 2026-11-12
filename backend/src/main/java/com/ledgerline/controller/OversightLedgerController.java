package com.ledgerline.controller;

import com.ledgerline.dto.ReconciliationResponse;
import com.ledgerline.dto.StatementResponse;
import com.ledgerline.service.ReconciliationService;
import com.ledgerline.service.StatementFilter;
import com.ledgerline.service.StatementService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Read-only ledger oversight for ADMIN and AUDITOR: any account's statement, and reconciliation. GET only. */
@RestController
@RequestMapping("/api/oversight")
public class OversightLedgerController {

    private final StatementService statements;
    private final ReconciliationService reconciliation;

    public OversightLedgerController(StatementService statements, ReconciliationService reconciliation) {
        this.statements = statements;
        this.reconciliation = reconciliation;
    }

    @GetMapping("/accounts/{accountId}/statement")
    public StatementResponse statement(@PathVariable UUID accountId,
            @RequestParam(defaultValue = "0") @Min(0) @Max(AccountController.MAX_PAGE) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(AccountController.MAX_PAGE_SIZE) int size,
            @RequestParam(required = false) @Pattern(regexp = StatementFilter.STATUSES) String status,
            @RequestParam(required = false) @Pattern(regexp = StatementFilter.OPERATIONS) String operation) {
        return statements.any(accountId, StatementFilter.of(status, operation), page, size);
    }

    @GetMapping("/reconciliation")
    public ReconciliationResponse reconcile() {
        return reconciliation.reconcile();
    }
}
