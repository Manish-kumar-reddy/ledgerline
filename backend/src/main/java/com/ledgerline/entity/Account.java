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
 * A customer account (table {@code accounts}, created by V4). It holds no balance.
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

    @Column(name = "owner_id", nullable = false, updatable = false)
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

    @Override
    public String toString() {
        return "Account[id=" + id + ", ownerId=" + ownerId + ", status=" + status + "]";
    }
}
