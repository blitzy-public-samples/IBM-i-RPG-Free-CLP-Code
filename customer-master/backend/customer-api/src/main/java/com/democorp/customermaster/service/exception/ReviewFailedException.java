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
 * a review failure has to tell it which State was accepted. This exception carries that value,
 * {@link #stateAccepted()}, beside the failure it wraps, {@link #cause()}.
 *
 * <p>Only {@code CustomerMaintenanceService.review} throws it, and its cause there is one of:
 * <ul>
 *   <li>a {@link CustomerValidationException} at rules 6 to 9 (DEM0502) or at
 *       {@link CustomerValidationException#ADDRESS_RULE} (DEM9898, or DEM0503 for the standardized State);</li>
 *   <li>the address module's {@code AddressServiceUnavailableException} (the source ended the program through
 *       SQLProblem, {@code Service_Pgms/SRV_SQL.SQLRPGLE:20-57}).</li>
 * </ul>
 * A failure at rules 1 to 5 precedes acceptance of the State, so it is thrown unwrapped and carries no
 * {@code stateAccepted}. The constructor rejects such a cause, and a nested {@code ReviewFailedException}.
 *
 * <p>{@code controller.ApiExceptionHandler} answers either of those causes with the cause's own problem (422 or 502)
 * plus {@code stateAccepted}. Any other cause the constructor accepts is unexpected, and is answered 500 DEM9999 with
 * an {@code errorId} and without {@code stateAccepted}.
 *
 * <p>{@link #getMessage()} is the cause's message, which for those two causes is a CUSTMSGF code or an address fault
 * kind and holds no user input. {@link #stateAccepted()} is never part of the message.
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
     * @param cause         the failure: any {@code RuntimeException} except a {@code ReviewFailedException} or a
     *                      {@link CustomerValidationException} at or before
     *                      {@link CustomerValidationException#STATE_RULE}. In production it is a
     *                      {@code CustomerValidationException} above that rule or the address module's
     *                      {@code AddressServiceUnavailableException}; any other cause is answered 500 DEM9999
     *                      without {@code stateAccepted}
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
     * Rejects a missing cause before the superclass is constructed, and takes the cause's message. For the two
     * production causes that message is a code or a fault kind and never SQL text, a URL or user input. An unexpected
     * cause's message has no such guarantee; its 500 is logged with every exception message withheld.
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
     * @return the wrapped failure, never {@code null}: normally a {@link CustomerValidationException} or the address
     *         module's {@code AddressServiceUnavailableException}, otherwise whatever {@code RuntimeException} the
     *         constructor accepted
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
