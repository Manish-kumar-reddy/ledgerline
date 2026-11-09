package com.ledgerline.service;

import com.ledgerline.dto.AccountResponse;
import com.ledgerline.dto.PageResponse;
import com.ledgerline.exception.AccountNotFoundException;
import com.ledgerline.repository.AccountRepository;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only view of every account, for ADMIN and AUDITOR only. Kept separate from {@link AccountService} so customer
 * code paths never reach an unscoped query. There are no write methods here.
 */
@Service
public class AccountOversightService {

    private final AccountRepository accounts;

    public AccountOversightService(AccountRepository accounts) {
        this.accounts = accounts;
    }

    @PreAuthorize("hasAnyRole('ADMIN', 'AUDITOR')")
    @Transactional(readOnly = true)
    public PageResponse<AccountResponse> listAll(int page, int size) {
        return PageResponse.from(accounts.findAll(PageRequest.of(page, size, AccountService.ORDER)), AccountResponse::from);
    }

    @PreAuthorize("hasAnyRole('ADMIN', 'AUDITOR')")
    @Transactional(readOnly = true)
    public AccountResponse get(UUID accountId) {
        return accounts.findById(accountId).map(AccountResponse::from).orElseThrow(AccountNotFoundException::new);
    }
}
