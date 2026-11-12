package com.ledgerline.service;

/**
 * Optional statement filters; {@code null} means "any". Values arrive already checked against {@link #STATUSES} and
 * {@link #OPERATIONS} by the controllers (exact, upper-case, single value), so {@link #of} never sees anything else.
 */
public record StatementFilter(Status status, Operation operation) {

    public enum Status { POSTED, REFUSED }

    public enum Operation { DEPOSIT, WITHDRAWAL, TRANSFER }

    /** Accepted request values, for {@code @Pattern} on the controllers' query parameters. */
    public static final String STATUSES = "POSTED|REFUSED";
    public static final String OPERATIONS = "DEPOSIT|WITHDRAWAL|TRANSFER";

    public static final StatementFilter NONE = new StatementFilter(null, null);

    public static StatementFilter of(String status, String operation) {
        return new StatementFilter(status == null ? null : Status.valueOf(status),
                operation == null ? null : Operation.valueOf(operation));
    }

    public boolean isEmpty() {
        return status == null && operation == null;
    }
}
