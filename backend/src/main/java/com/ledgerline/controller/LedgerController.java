package com.ledgerline.controller;

import com.ledgerline.dto.MoneyMovementRequest;
import com.ledgerline.dto.PostingResponse;
import com.ledgerline.dto.StatementResponse;
import com.ledgerline.dto.TransferRequest;
import com.ledgerline.money.Money;
import com.ledgerline.service.LedgerService;
import com.ledgerline.service.PostingResult;
import com.ledgerline.service.StatementService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Money movement and statements on the caller's own account (ACCOUNT_HOLDER only). Every money request needs an
 * Idempotency-Key header. 201 = posted, 422 = refused (recorded on the statement), 409 = key reused with a different
 * request, 503 = ledger busy (nothing committed; retry with the same key), 404 = not your account.
 */
@RestController
@RequestMapping("/api/accounts/{accountId}")
public class LedgerController {

    public static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    public static final String REPLAYED = "Idempotent-Replayed";
    static final String KEY_REGEX = "[!-~]{1,100}";

    private final LedgerService ledger;
    private final StatementService statements;

    public LedgerController(LedgerService ledger, StatementService statements) {
        this.ledger = ledger;
        this.statements = statements;
    }

    @PostMapping("/deposits")
    public ResponseEntity<PostingResponse> deposit(@PathVariable UUID accountId,
            @RequestHeader(IDEMPOTENCY_KEY) @Pattern(regexp = KEY_REGEX) String key,
            @Valid @RequestBody MoneyMovementRequest request) {
        return respond(ledger.deposit(accountId, Money.toMinor(request.amount()), request.description(), key));
    }

    @PostMapping("/withdrawals")
    public ResponseEntity<PostingResponse> withdraw(@PathVariable UUID accountId,
            @RequestHeader(IDEMPOTENCY_KEY) @Pattern(regexp = KEY_REGEX) String key,
            @Valid @RequestBody MoneyMovementRequest request) {
        return respond(ledger.withdraw(accountId, Money.toMinor(request.amount()), request.description(), key));
    }

    @PostMapping("/transfers")
    public ResponseEntity<PostingResponse> transfer(@PathVariable UUID accountId,
            @RequestHeader(IDEMPOTENCY_KEY) @Pattern(regexp = KEY_REGEX) String key,
            @Valid @RequestBody TransferRequest request) {
        return respond(ledger.transfer(accountId, request.toAccountId(), Money.toMinor(request.amount()),
                request.description(), key));
    }

    @GetMapping("/statement")
    public StatementResponse statement(@PathVariable UUID accountId,
            @RequestParam(defaultValue = "0") @Min(0) @Max(AccountController.MAX_PAGE) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(AccountController.MAX_PAGE_SIZE) int size) {
        return statements.own(accountId, page, size);
    }

    static ResponseEntity<PostingResponse> respond(PostingResult result) {
        ResponseEntity.BodyBuilder response =
                ResponseEntity.status(result.posted() ? HttpStatus.CREATED : HttpStatus.UNPROCESSABLE_CONTENT);
        if (result.replayed()) {
            response.header(REPLAYED, "true");
        }
        return response.body(PostingResponse.from(result));
    }
}
