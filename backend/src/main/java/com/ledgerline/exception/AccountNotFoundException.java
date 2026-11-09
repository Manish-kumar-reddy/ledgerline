package com.ledgerline.exception;

/**
 * 404 for an account that does not exist <em>or</em> belongs to someone else. The two cases are deliberately the
 * same exception with the same response, so a caller cannot learn whether another user's account id exists.
 */
public class AccountNotFoundException extends RuntimeException {

    public AccountNotFoundException() {
        super("Account not found");
    }
}
