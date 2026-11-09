package com.ledgerline.exception;

/** 400 for a request that is well-formed JSON but cannot be a valid posting (e.g. a transfer to the same account). */
public class InvalidLedgerRequestException extends RuntimeException {

    public InvalidLedgerRequestException(String message) {
        super(message);
    }
}
