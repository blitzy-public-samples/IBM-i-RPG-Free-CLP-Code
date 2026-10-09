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
 * ordinal of {@code 9999}, so {@code nextval} past it raises SQLSTATE
 * {@code 2200H} and no id is reissued by wrap-around. A generator load whose
 * last id is {@code 9999} leaves the sequence exhausted as well.
 * {@code repository.CustomerIdAllocator} translates {@code 2200H} into this
 * exception, and {@code ApiExceptionHandler} maps it to HTTP 503
 * {@code APP0503}.
 *
 * <p><b>What it carries.</b> The message is the catalog key {@code APP0503}
 * only. The exception holds no SQLSTATE, SQL text or id value. The optional
 * cause, the JDBC or Spring data-access exception that carried SQLSTATE
 * {@code 2200H}, is kept for server-side logging and never reaches a response
 * body.
 *
 * <p>This class imports nothing outside {@code java.*}:
 * {@code CustomerIdAllocator} in the repository layer throws it, so a
 * dependency in the other direction would create a cycle.
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
