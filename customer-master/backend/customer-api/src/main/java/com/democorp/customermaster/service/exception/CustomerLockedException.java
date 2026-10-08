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
 * <p>Created by {@code CustomerMaintenanceService} when an add or an update waits past
 * the lock timeout, whether on the id-allocation guard, the insert or the versioned
 * update, and by {@code controller.ApiExceptionHandler} for an add or update whose
 * connection-pool borrow timed out in a saturated pool, a lock wait in another form.
 * A lock timeout inside {@code CustomerRepository.save} arrives wrapped in Spring Data's
 * {@code DbActionExecutionException}, and Spring's PostgreSQL error codes leave
 * {@code 55P03} uncategorized, so callers test the SQLSTATE in the cause chain.
 * {@code ApiExceptionHandler} maps it to HTTP 409 {@code DEM1001}.
 *
 * <p>The source passed {@code SQLERRMC} as message data, but the DEM1001 text has no
 * {@code &1} substitution variable ({@code 5250_Subfile/CRTMSGF.CLLE}), so that data was
 * never shown. The target therefore sends no data with DEM1001: the exception message
 * is the catalog code alone and never holds SQL text, an SQLSTATE or {@code SQLERRMC}.
 * The optional cause is kept for server-side diagnostics only; it is never exposed to a
 * client.
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
