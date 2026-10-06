package com.democorp.customermaster.service.exception;

/**
 * Signals that the 4-character base-36 customer id space is used up, so no new
 * customer can be added.
 *
 * <p><b>Source behaviour replaced.</b> On IBM i, AddRecd in MTNCUSTR locks the
 * CUSTNEXT data area, increments it with BASE36ADD and inserts the result, with
 * no limit check. BASE36ADD leaves that check to its caller and, given the
 * maximum value {@code 9999}, silently rolls over to {@code AAAA}. The source
 * therefore reissues ids from {@code AAAA} upward, and an add fails through
 * SQLProblem only when the reissued id happens to exist already.
 *
 * <p><b>Target behaviour.</b> Ids come from the PostgreSQL sequence
 * {@code custmast_id_seq}, declared {@code MAXVALUE 1679615 NO CYCLE}, the
 * ordinal of {@code 9999}. Calling {@code nextval} past {@code MAXVALUE} raises
 * SQLSTATE {@code 2200H} (sequence generator limit exceeded), and
 * {@code CustomerIdAllocator.next()} translates that error into this exception.
 * The same condition follows a generator load whose last id is {@code 9999},
 * because the load leaves the sequence exhausted. Ids are never reissued.
 *
 * <p><b>HTTP mapping.</b> {@code ApiExceptionHandler} maps this exception,
 * through {@code ProblemFactory}, to {@code 503 Service Unavailable} with
 * catalog key {@code APP0503} ("No customer ids are left. Contact IT."). No
 * other class maps it.
 *
 * <p><b>What it carries.</b> The message is the catalog key {@code APP0503}
 * only. The exception holds no SQLSTATE, SQL text or id value. The optional
 * cause, the JDBC or Spring data-access exception that carried SQLSTATE
 * {@code 2200H}, is kept for server-side logging and never reaches a response
 * body.
 *
 * <p>This package imports nothing outside {@code java.*}:
 * {@code CustomerIdAllocator} in the repository layer throws this class, so a
 * dependency in the other direction would create a cycle.
 *
 * <pre>{@code
 * try {
 *     ordinal = jdbc.queryForObject("SELECT nextval('custmast_id_seq')", Map.of(), Integer.class);
 * } catch (DataAccessException e) {
 *     if (isSequenceLimitExceeded(e)) {          // SQLSTATE 2200H
 *         throw new CustomerIdExhaustedException(e);
 *     }
 *     throw e;
 * }
 * }</pre>
 */
public final class CustomerIdExhaustedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Catalog key of the problem this exception becomes, and its only message. */
    private static final String CODE = "APP0503";

    /**
     * Creates the exception with no cause, for callers that detect exhaustion
     * without an underlying database error.
     */
    public CustomerIdExhaustedException() {
        super(CODE);
    }

    /**
     * Creates the exception around the database error that reported the
     * exhausted sequence.
     *
     * @param cause the data-access exception carrying SQLSTATE {@code 2200H};
     *              kept for server-side logs only
     */
    public CustomerIdExhaustedException(Throwable cause) {
        super(CODE, cause);
    }
}
