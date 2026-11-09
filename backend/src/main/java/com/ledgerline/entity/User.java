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
 * A user who can sign in. Mirrors the {@code users} table created by V3.
 *
 * The application can only insert id, full name, email and password hash: that is all the runtime database
 * role is granted. Role, enabled and created_at are never written by the application ({@code insertable =
 * false}); PostgreSQL fills them from the column defaults and Hibernate reads them back after the insert.
 * Every column is {@code updatable = false}, so Hibernate never issues an UPDATE (which the database would
 * refuse anyway). {@code @Immutable} is not used: Hibernate cannot combine it with reading back the
 * database-generated columns after the insert ("Lock mode WRITE not supported for read-only entity").
 */
@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "full_name", nullable = false, length = 100, updatable = false)
    private String fullName;

    @Column(name = "email", nullable = false, length = 254, updatable = false)
    private String email;

    @Column(name = "password_hash", nullable = false, length = 60, updatable = false)
    private String passwordHash;

    @Generated
    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 20, insertable = false, updatable = false)
    private Role role;

    @Generated
    @Column(name = "enabled", nullable = false, insertable = false, updatable = false)
    private Boolean enabled;

    @Generated
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    protected User() {
        // For JPA.
    }

    /** {@code email} must already be normalised (trimmed, lower-case); the database rejects anything else. */
    public User(String fullName, String email, String passwordHash) {
        this.fullName = fullName;
        this.email = email;
        this.passwordHash = passwordHash;
    }

    public UUID getId() {
        return id;
    }

    public String getFullName() {
        return fullName;
    }

    public String getEmail() {
        return email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public Role getRole() {
        return role;
    }

    public boolean isEnabled() {
        return Boolean.TRUE.equals(enabled);
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    @Override
    public String toString() {
        return "User[id=" + id + ", role=" + role + ", enabled=" + enabled + "]";
    }
}
