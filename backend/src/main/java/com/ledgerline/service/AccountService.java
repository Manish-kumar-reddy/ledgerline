package com.ledgerline.service;

import com.ledgerline.dto.AccountResponse;
import com.ledgerline.dto.CreateAccountRequest;
import com.ledgerline.dto.PageResponse;
import com.ledgerline.entity.Account;
import com.ledgerline.exception.AccountNotFoundException;
import com.ledgerline.repository.AccountRepository;
import com.ledgerline.security.CurrentUser;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * An account holder's own accounts. Every method:
 * <ul>
 *   <li>requires ROLE_ACCOUNT_HOLDER ({@code @PreAuthorize}, in addition to the URL rule in SecurityConfig);</li>
 *   <li>takes the owner only from {@link CurrentUser}, never from the request;</li>
 *   <li>reads only through owner-scoped queries, so another user's account is simply "not found".</li>
 * </ul>
 */
@Service
public class AccountService {

    /** Fixed order, so pages are stable and a client cannot sort by arbitrary columns. */
    static final Sort ORDER = Sort.by("createdAt", "id");

    private final AccountRepository accounts;

    public AccountService(AccountRepository accounts) {
        this.accounts = accounts;
    }

    @PreAuthorize("hasRole('ACCOUNT_HOLDER')")
    @Transactional
    public AccountResponse create(CreateAccountRequest request) {
        UUID ownerId = CurrentUser.require().id();
        return AccountResponse.from(accounts.saveAndFlush(new Account(ownerId, request.name())));
    }

    @PreAuthorize("hasRole('ACCOUNT_HOLDER')")
    @Transactional(readOnly = true)
    public PageResponse<AccountResponse> listOwn(int page, int size) {
        UUID ownerId = CurrentUser.require().id();
        return PageResponse.from(accounts.findByOwnerId(ownerId, PageRequest.of(page, size, ORDER)), AccountResponse::from);
    }

    @PreAuthorize("hasRole('ACCOUNT_HOLDER')")
    @Transactional(readOnly = true)
    public AccountResponse getOwn(UUID accountId) {
        UUID ownerId = CurrentUser.require().id();
        return accounts.findByIdAndOwnerId(accountId, ownerId)
                .map(AccountResponse::from)
                .orElseThrow(AccountNotFoundException::new);
    }
}
