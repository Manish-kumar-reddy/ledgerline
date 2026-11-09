package com.ledgerline.entity;

/** Matches the CHECK constraint in V4. Only ACTIVE is ever set in Phase 4 (the database default). */
public enum AccountStatus {
    ACTIVE,
    FROZEN,
    CLOSED
}
