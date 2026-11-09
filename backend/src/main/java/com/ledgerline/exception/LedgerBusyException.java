package com.ledgerline.exception;

/**
 * 503: the posting could not get its account locks in time (or hit a deadlock). The database transaction was rolled
 * back completely, including the idempotency key, so retrying with the same key is safe.
 */
public class LedgerBusyException extends RuntimeException {

    public LedgerBusyException() {
        super("Ledger busy");
    }
}
