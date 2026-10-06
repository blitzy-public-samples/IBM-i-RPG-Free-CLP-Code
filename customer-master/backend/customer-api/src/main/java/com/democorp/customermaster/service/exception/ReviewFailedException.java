package com.democorp.customermaster.service.exception;

import java.util.Objects;

/**
 * A customer review failed after the input State had already passed the State rule.
 *
 * <p>In MTNCUSTR, {@code Edit_SD_STATE} moves a valid screen State into the program's working {@code STATE}
 * ({@code 5250_Subfile/MTNCUSTR.SQLRPGLE:488-494}), and that value survives any later failure in the same
 * {@code EditUpdData} pass. When the user afterwards cancels a State prompt, {@code F04Prompt} copies the working
 * {@code STATE} back into the screen field ({@code 5250_Subfile/MTNCUSTR.SQLRPGLE:369-375}), discarding whatever was
 * typed since. The target API keeps no per-caller state between requests, so the browser owns that working State, and
 * a review failure has to tell it which State was accepted. This exception carries that value, {@link #stateAccepted()},
 * beside the failure it wraps, {@link #cause()}.
 *
 * <h2>What it wraps</h2>
 * <ul>
 *   <li>a {@link CustomerValidationException} at rules 6 to 9 ({@code zip}, {@code acctPhone}, {@code acctMgr},
 *       {@code corpPhone}; DEM0502);</li>
 *   <li>a {@link CustomerValidationException} at {@link CustomerValidationException#ADDRESS_RULE rule 10}: DEM9898
 *       when the address could not be standardized, or DEM0503 when the standardized State is not in STATES;</li>
 *   <li>the address module's {@code AddressServiceUnavailableException}, raised for a USPS transport failure or a
 *       response that breaks the response rules (the source ended the program through SQLProblem,
 *       {@code Service_Pgms/SRV_SQL.SQLRPGLE:20-57}).</li>
 * </ul>
 *
 * <p>A failure at rules 1 to 5 ({@code active}, {@code name}, {@code addr}, {@code city} and the State rule itself)
 * happens before Edit_SD_STATE accepts anything, so it is thrown unwrapped and carries no {@code stateAccepted}. The
 * constructor rejects such a cause, so that rule cannot be broken by accident. Nor does it accept another
 * {@code ReviewFailedException}: a failure is wrapped at most once.
 *
 * <h2>Thrower and HTTP mapping</h2>
 * <p>Only {@code CustomerMaintenanceService.review} throws this exception. Only {@code controller.ApiExceptionHandler}
 * maps it. The handler builds the problem for {@link #cause()} exactly as it would for that exception thrown alone,
 * then adds the problem member {@code stateAccepted} through {@code ProblemFactory.withStateAccepted}:
 * <table>
 *   <caption>Problem built per cause</caption>
 *   <tr><th>Cause</th><th>Problem</th></tr>
 *   <tr><td>{@code CustomerValidationException}, rules 6 to 9 or rule 10</td>
 *       <td>422 with the cause's code (DEM0502, DEM0503 or DEM9898), args and {@code errors}</td></tr>
 *   <tr><td>{@code AddressServiceUnavailableException}</td><td>502 APP0502, no {@code errors}</td></tr>
 *   <tr><td>anything else</td><td>500 DEM9999 with an {@code errorId}, logged at ERROR</td></tr>
 * </table>
 * This class carries no HTTP status and no message text.
 *
 * <h2>Message safety</h2>
 * <p>{@link #getMessage()} is the cause's message: the CUSTMSGF code for a {@code CustomerValidationException}, the
 * fault kind for the address module's exception. Neither holds SQL text, a URL, credentials or customer data, and
 * {@link #stateAccepted()} is deliberately kept out of the message, so logging this exception writes no user input.
 *
 * <pre>{@code
 * try {
 *     validator.validate(normalized);
 * } catch (CustomerValidationException e) {
 *     if (e.rule() > CustomerValidationException.STATE_RULE) {
 *         throw new ReviewFailedException(e, normalized.address().state());
 *     }
 *     throw e;
 * }
 * }</pre>
 *
 * <p>Instances are immutable and safe to share between threads.
 */
public final class ReviewFailedException extends RuntimeException {

    /** Serialization version of this exception type. */
    private static final long serialVersionUID = 1L;

    /**
     * The wrapped failure, kept with its own type so the handler can map it without a cast. It is the same object
     * {@link #getCause()} returns.
     */
    private final RuntimeException cause;

    /** The normalized input State that passed the State rule before the failure. */
    private final String stateAccepted;

    /**
     * Wraps a review failure raised after the State rule passed.
     *
     * @param cause         the failure: a {@link CustomerValidationException} above
     *                      {@link CustomerValidationException#STATE_RULE}, or the address module's
     *                      {@code AddressServiceUnavailableException}; never another {@code ReviewFailedException}
     * @param stateAccepted the normalized input {@code state} that passed the State rule, a 2-character code such as
     *                      {@code CA}; stored as given
     * @throws NullPointerException     if {@code cause} or {@code stateAccepted} is {@code null}
     * @throws IllegalArgumentException if {@code cause} is a {@code ReviewFailedException}, if {@code cause} is a
     *                                  {@link CustomerValidationException} at or before the State rule, or if
     *                                  {@code stateAccepted} is blank
     */
    public ReviewFailedException(RuntimeException cause, String stateAccepted) {
        super(messageOf(cause), cause);
        if (cause instanceof ReviewFailedException) {
            throw new IllegalArgumentException("cause must not itself be a ReviewFailedException");
        }
        if (cause instanceof CustomerValidationException validation
                && validation.rule() <= CustomerValidationException.STATE_RULE) {
            throw new IllegalArgumentException("a failure at rule " + validation.rule()
                    + " precedes acceptance of the State and carries no stateAccepted");
        }
        Objects.requireNonNull(stateAccepted, "stateAccepted");
        if (stateAccepted.isBlank()) {
            throw new IllegalArgumentException("stateAccepted must not be blank");
        }
        this.cause = cause;
        this.stateAccepted = stateAccepted;
    }

    /**
     * Rejects a missing cause before the superclass is constructed, and takes the cause's message, which is a code or
     * a fault kind and never SQL text, a URL or user input.
     *
     * @param cause the failure being wrapped
     * @return the cause's message, which may be {@code null} when the cause has none
     * @throws NullPointerException if {@code cause} is {@code null}
     */
    private static String messageOf(RuntimeException cause) {
        return Objects.requireNonNull(cause, "cause").getMessage();
    }

    /**
     * Returns the wrapped failure, the same object as {@link #getCause()} but typed.
     *
     * @return the {@link CustomerValidationException} or {@code AddressServiceUnavailableException}; never
     *         {@code null}
     */
    public RuntimeException cause() {
        return cause;
    }

    /**
     * Returns the State the UI keeps as its working State after this failure.
     *
     * @return the normalized input {@code state} that passed the State rule; never {@code null} or blank
     */
    public String stateAccepted() {
        return stateAccepted;
    }
}
