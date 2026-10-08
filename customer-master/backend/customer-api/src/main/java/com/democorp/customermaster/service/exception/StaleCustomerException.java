package com.democorp.customermaster.service.exception;

import com.democorp.customermaster.domain.Customer;
import java.util.Objects;

/**
 * An update was based on a version of the customer that is no longer the stored one, answered with
 * HTTP 409 and code {@code DEM1002}.
 *
 * <p><b>Source behaviour replaced.</b> The IBM i maintenance program's {@code UpdateRecd} rewrites the
 * row with {@code UPDATE CUSTMAST ... WHERE CUSTID = :CUSTID AND CHGTIME = :Orig_CHGTIME}, so a row
 * someone else changed since it was read matches nothing. On {@code SQLNODATA} it sends {@code DEM1002}
 * "Someone else changed record. Review data." to the message subfile and calls
 * {@code ReadRecd(); FillScreenFields();} to show the changed data for review
 * ({@code 5250_Subfile/MTNCUSTR.SQLRPGLE:593-599}; text from {@code 5250_Subfile/CRTMSGF.CLLE:40-41},
 * with the "Rewiew" typo and the double space corrected in the message catalog). The target keeps the
 * same optimistic check with an integer token instead of the timestamp:
 * {@code UPDATE custmast ... WHERE custid = ? AND row_version = ?}. When that changes zero rows, the row
 * is re-read in the same transaction, and this exception carries the re-read row, {@link #current()},
 * in place of the source's {@code ReadRecd}. No lock is held across the user's think time, as in the
 * source.
 *
 * <p><b>Thrower.</b> {@code CustomerMaintenanceService.update}, when the versioned {@code UPDATE} changes
 * no row and the re-read finds the row; a re-read that finds nothing raises
 * {@link CustomerNotFoundException} instead. {@code controller.ApiExceptionHandler} maps it to HTTP 409
 * {@code DEM1002}, with {@link #current()} rendered as the problem's {@code current} member, including its
 * {@code version}.
 *
 * <p><b>What never travels.</b> The exception message is the catalog code {@code DEM1002} alone. It
 * holds no SQL text, no SQLSTATE and no customer data: neither {@link #getMessage()} nor
 * {@link #toString()} renders the stored row, so a log line written for this exception records no
 * customer data.
 *
 * <p>The package depends only on the JDK and the domain types, which import nothing from the service
 * layer, so the maintenance service can throw it without a dependency cycle.
 */
public final class StaleCustomerException extends RuntimeException {

    /** Serialization version of this exception type. */
    private static final long serialVersionUID = 1L;

    /** Catalog key of the message the client receives; used as the exception message. */
    private static final String CODE = "DEM1002";

    /**
     * The customer as now stored, re-read after the versioned update matched no row. Transient because
     * {@link Customer} is not declared {@link java.io.Serializable}; the exception is never serialized in
     * this application.
     */
    private final transient Customer current;

    /**
     * Creates the exception for an update whose version no longer matches the stored row.
     *
     * @param current the customer as now stored, including its {@link Customer#rowVersion() row version}
     * @throws NullPointerException if {@code current} is {@code null}
     */
    public StaleCustomerException(Customer current) {
        super(CODE);
        this.current = Objects.requireNonNull(current, "current");
    }

    /**
     * Returns the customer as now stored, which the error response carries as {@code current} so the
     * user can review it.
     *
     * @return the stored customer, including its {@link Customer#rowVersion() row version};
     *         {@code null} only on an instance restored by Java serialization, which drops the transient
     *         field
     */
    public Customer current() {
        return current;
    }
}
