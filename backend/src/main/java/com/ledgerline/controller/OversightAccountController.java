package com.ledgerline.controller;

import com.ledgerline.dto.AccountResponse;
import com.ledgerline.dto.PageResponse;
import com.ledgerline.service.AccountOversightService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Read-only view of all accounts for ADMIN and AUDITOR. GET only; any other method is denied by SecurityConfig. */
@RestController
@RequestMapping("/api/oversight/accounts")
public class OversightAccountController {

    private final AccountOversightService oversight;

    public OversightAccountController(AccountOversightService oversight) {
        this.oversight = oversight;
    }

    @GetMapping
    public PageResponse<AccountResponse> list(
            @RequestParam(defaultValue = "0") @Min(0) @Max(AccountController.MAX_PAGE) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(AccountController.MAX_PAGE_SIZE) int size) {
        return oversight.listAll(page, size);
    }

    @GetMapping("/{id}")
    public AccountResponse get(@PathVariable UUID id) {
        return oversight.get(id);
    }
}
