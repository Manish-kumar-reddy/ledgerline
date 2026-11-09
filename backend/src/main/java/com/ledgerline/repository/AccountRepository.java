package com.ledgerline.repository;

import com.ledgerline.entity.Account;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.Repository;

/**
 * Deliberately a plain {@link Repository} exposing only these methods: there is no delete, no generic save-as-update
 * and no unscoped "find all" for customer code to reach by mistake.
 *
 * Customer reads use only the {@code ...OwnerId} methods. {@link #findById} and {@link #findAll} are for oversight
 * (ADMIN and AUDITOR) only, through AccountOversightService.
 */
public interface AccountRepository extends Repository<Account, UUID> {

    Account saveAndFlush(Account account);

    Optional<Account> findByIdAndOwnerId(UUID id, UUID ownerId);

    Page<Account> findByOwnerId(UUID ownerId, Pageable pageable);

    Optional<Account> findById(UUID id);

    Page<Account> findAll(Pageable pageable);
}
