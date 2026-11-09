package com.ledgerline.money;

import java.math.BigDecimal;
import java.util.regex.Pattern;

/**
 * Rupee amounts at the API boundary. Money is carried as a JSON string ("1500.50") and converted exactly to integer
 * paise; floating point is never involved. Limits (README.md, "Money limits") are enforced by PostgreSQL.
 */
public final class Money {

    /** A positive amount: up to 9 rupee digits, at most 2 decimal places, no sign, no exponent, not zero. */
    public static final String AMOUNT_REGEX = "(?!0+(\\.0{1,2})?$)(0|[1-9][0-9]{0,8})(\\.[0-9]{1,2})?";

    private static final Pattern AMOUNT = Pattern.compile(AMOUNT_REGEX);

    private Money() {
    }

    /** Exact conversion to paise; the input must already match {@link #AMOUNT_REGEX}. */
    public static long toMinor(String amount) {
        if (amount == null || !AMOUNT.matcher(amount).matches()) {
            throw new IllegalArgumentException("Not a valid amount");
        }
        return new BigDecimal(amount).movePointRight(2).longValueExact();
    }

    /** Paise to a rupee string with exactly two decimals, e.g. -30000 -> "-300.00". */
    public static String format(long minor) {
        return BigDecimal.valueOf(minor).movePointLeft(2).setScale(2).toPlainString();
    }
}
