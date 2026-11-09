package com.ledgerline.exception;

/** 409: the idempotency key was already used by this user for a different request. Nothing was posted. */
public class IdempotencyConflictException extends RuntimeException {

    public IdempotencyConflictException() {
        super("Idempotency key reused with a different request");
    }
}
