package com.ledgerline.validation;

import java.util.Locale;

/** The one place emails are normalised, so registration, login and the database constraint always agree. */
public final class Emails {

    private Emails() {
    }

    /** Trims and lower-cases; null stays null (validation reports it). */
    public static String normalise(String email) {
        return email == null ? null : email.strip().toLowerCase(Locale.ROOT);
    }
}
