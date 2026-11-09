package com.ledgerline.exception;

import java.time.Duration;

/** A rate limit was reached (429). Carries the response title and how long to wait (for Retry-After). */
public class TooManyAttemptsException extends RuntimeException {

    private final String title;
    private final Duration retryAfter;

    public TooManyAttemptsException(String title, Duration retryAfter) {
        super(title);
        this.title = title;
        this.retryAfter = retryAfter;
    }

    public String getTitle() {
        return title;
    }

    public Duration getRetryAfter() {
        return retryAfter;
    }
}
