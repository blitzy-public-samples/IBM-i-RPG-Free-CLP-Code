package com.democorp.customermaster.service.exception;

/**
 * A customer write could not obtain its lock within the configured lock timeout.
 *
 * <p>Replaces the row-locked branch of the IBM i maintenance program's
 * {@code UpdateRecd} procedure ({@code 5250_Subfile/MTNCUSTR.SQLRPGLE}): when the
 * {@code UPDATE CUSTMAST} returned {@code SQLSTATE 57033} ({@code SQLROWLOCKED}), the
 * program sent message {@code DEM1001} to the message subfile and returned the user to
 * the editable screen with the entries kept. In PostgreSQL the same condition surfaces
 * as {@code SQLSTATE 55P03} once {@code SET LOCAL lock_timeout}
 * ({@code customer-master.db.lock-timeout}, 5 seconds by default) expires.
 *
 * <p>Thrown by {@code CustomerMaintenanceService.update} when the versioned
 * {@code UPDATE} waits on a row another transaction holds, and by
 * {@code CustomerMaintenanceService.add} when the id-allocation guard waits on a
 * running test-data load. {@code controller.ApiExceptionHandler} maps it, through
 * {@code controller.ProblemFactory}, to HTTP 409 with code {@code DEM1001}
 * ("Customer being updated by another user or job."). The mapping lives only there:
 * this class carries no HTTP status and no message text.
 *
 * <p>The source passed {@code SQLERRMC} as message data, but the DEM1001 text has no
 * {@code &1} substitution variable ({@code 5250_Subfile/CRTMSGF.CLLE}), so that data was
 * never shown. The target therefore sends no data with DEM1001: the exception message
 * is the catalog code alone and never holds SQL text, an SQLSTATE or {@code SQLERRMC}.
 * The optional cause, typically Spring's {@code CannotAcquireLockException}, is kept
 * only so the server log can record it; it is never exposed to a client.
 *
 * <p>The package depends on the JDK alone, so repository and service classes can throw
 * these exceptions without introducing a dependency cycle.
 *
 * <pre>{@code
 * try {
 *     return repository.save(customer);
 * } catch (CannotAcquireLockException e) {
 *     throw new CustomerLockedException(e);
 * }
 * }</pre>
 */
public final class CustomerLockedException extends RuntimeException {

    /** Serialization version of this exception type. */
    private static final long serialVersionUID = 1L;

    /** Catalog key of the message the client receives; used as the exception message. */
    private static final String CODE = "DEM1001";

    /** Creates the exception without a cause. */
    public CustomerLockedException() {
        super(CODE);
    }

    /**
     * Creates the exception, keeping the lock failure for server-side logging only.
     *
     * @param cause the underlying lock-timeout failure, for example a
     *              {@code CannotAcquireLockException} carrying SQLSTATE 55P03
     */
    public CustomerLockedException(Throwable cause) {
        super(CODE, cause);
    }
}
