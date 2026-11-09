package com.ledgerline.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Generated;

/**
 * An account (table {@code accounts}, V4 and V5). Customer accounts belong to an account holder; the single SYSTEM
 * account is the simulated settlement cash and has no owner.
 *
 * The balance and the statement counter are read-only here: only the ledger posting functions in PostgreSQL change
 * them (the runtime role has no UPDATE privilege on this table). balance = credits - debits, in paise.
 *
 * The application can only insert id, owner and name, matching the runtime role's column grants. Currency, status
 * and created_at always come from the database defaults ({@code insertable = false}) and are read back after the
 * insert. Every column is {@code updatable = false}: the runtime role has no UPDATE privilege on this table.
 * The owner is stored as a plain id (no association), so loading an account never loads user data.
 */
@Entity
@Table(name = "accounts")
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "owner_id", updatable = false)
    private UUID ownerId;

    @Column(name = "name", nullable = false, length = 100, updatable = false)
    private String name;

    @Generated
    @Column(name = "currency", nullable = false, length = 3, insertable = false, updatable = false)
    private String currency;

    @Generated
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20, insertable = false, updatable = false)
    private AccountStatus status;

    @Generated
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    @Generated
    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 10, insertable = false, updatable = false)
    private AccountKind kind;

    @Generated
    @Column(name = "balance_minor", nullable = false, insertable = false, updatable = false)
    private Long balanceMinor;

    @Generated
    @Column(name = "last_seq", nullable = false, insertable = false, updatable = false)
    private Long lastSeq;

    protected Account() {
        // For JPA.
    }

    /** {@code ownerId} must be the authenticated user's id; nothing else may decide it. */
    public Account(UUID ownerId, String name) {
        this.ownerId = ownerId;
        this.name = name;
    }

    public UUID getId() {
        return id;
    }

    public UUID getOwnerId() {
        return ownerId;
    }

    public String getName() {
        return name;
    }

    public String getCurrency() {
        return currency;
    }

    public AccountStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public AccountKind getKind() {
        return kind;
    }

    /** Paise; never null once read from the database. */
    public long getBalanceMinor() {
        return balanceMinor == null ? 0 : balanceMinor;
    }

    /** Number of statement lines (posted and refused). */
    public long getLastSeq() {
        return lastSeq == null ? 0 : lastSeq;
    }

    @Override
    public String toString() {
        return "Account[id=" + id + ", ownerId=" + ownerId + ", status=" + status + "]";
    }
}
