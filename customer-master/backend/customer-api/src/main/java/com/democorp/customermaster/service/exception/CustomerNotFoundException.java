package com.democorp.customermaster.service.exception;

import com.democorp.customermaster.domain.CustomerId;
import java.util.Objects;

/**
 * The customer a request names does not exist, answered with HTTP 404 and code {@code DEM0599}.
 *
 * <p><b>Source behaviour replaced.</b> The IBM i maintenance program reads the row by key in
 * {@code ReadRecd} and handles a missing row in two ways ({@code 5250_Subfile/MTNCUSTR.SQLRPGLE}):
 * <ul>
 *   <li>Display and Edit entry (lines 181-185 and the matching Edit branch): {@code SQLSTATE} NODATA
 *       calls {@code SQLProblem}, which dumps the program and ends it with an escape message carrying
 *       the SQLSTATE and SQL message text ({@code Service_Pgms/SRV_SQL.SQLRPGLE:20-57}).</li>
 *   <li>F5 re-read in Edit (lines 209-216): a row that vanished meanwhile sends {@code DEM0599}
 *       "Customer deleted. Exit &amp; redo search." ({@code 5250_Subfile/CRTMSGF.CLLE:36}) and
 *       clears the record.</li>
 * </ul>
 * A stateless request cannot end a program, and errors are typed exceptions mapped to HTTP codes, so
 * every missing-row case becomes this one exception and the one message the source already defines for
 * it. The program dump and the SQL diagnostics are not reproduced.
 *
 * <p><b>Throwers.</b> {@code CustomerMaintenanceService.get} and {@code CustomerMaintenanceService.update}
 * when the id names no stored row; for an update, the re-read after a versioned {@code UPDATE} that
 * changed zero rows finds nothing. A re-read that does find the row raises
 * {@code StaleCustomerException} instead. {@code controller.ApiExceptionHandler} maps it to HTTP 404
 * {@code DEM0599}.
 *
 * <p><b>What never travels.</b> The exception message is the catalog code {@code DEM0599} alone. It
 * holds no SQL text, no SQLSTATE and not the requested id, so a log line or a stray
 * {@code toString()} writes no request input. The id is available to server-side code through
 * {@link #id()}.
 *
 * <p>The package depends only on the JDK and the domain types, which import nothing from the service
 * layer, so repositories and services can throw it without a dependency cycle.
 */
public final class CustomerNotFoundException extends RuntimeException {

    /** Serialization version of this exception type. */
    private static final long serialVersionUID = 1L;

    /** Catalog key of the message the client receives; used as the exception message. */
    private static final String CODE = "DEM0599";

    /**
     * The id that was requested and not found. Transient because {@link CustomerId} is not declared
     * {@link java.io.Serializable}; the exception is never serialized in this application.
     */
    private final transient CustomerId id;

    /**
     * Creates the exception for a customer id with no stored row.
     *
     * @param id the requested id that was not found
     * @throws NullPointerException if {@code id} is {@code null}
     */
    public CustomerNotFoundException(CustomerId id) {
        super(CODE);
        this.id = Objects.requireNonNull(id, "id");
    }

    /**
     * Returns the id that was requested and not found, for server-side use such as logging.
     *
     * @return the requested id; {@code null} only on an instance restored by Java serialization,
     *         which drops the transient field
     */
    public CustomerId id() {
        return id;
    }
}
