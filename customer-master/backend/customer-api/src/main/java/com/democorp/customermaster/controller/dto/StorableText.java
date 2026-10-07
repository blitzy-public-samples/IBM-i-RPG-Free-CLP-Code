package com.democorp.customermaster.controller.dto;

import static java.lang.annotation.ElementType.ANNOTATION_TYPE;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

/**
 * Rejects text a PostgreSQL text column cannot store as sent: a value containing the character
 * U+0000 (NUL) or an unpaired UTF-16 surrogate. A {@code null} value is valid.
 *
 * <p><b>Why NUL.</b> PostgreSQL's {@code varchar} and {@code char} types cannot hold NUL, and the
 * driver reports such a value as SQLSTATE 22021 only when the statement runs. A JSON string can carry
 * it as the escape backslash-{@code u0000}, and it passes the length limit, the business rules (it is
 * not whitespace, so the value is not blank) and normalization. Without this check, a review would
 * accept a value that can never be saved, and an add would fail at its {@code INSERT} as 500 DEM9999
 * after the customer id was already allocated.
 *
 * <p><b>Why unpaired surrogates.</b> A JSON escape such as backslash-{@code ud800} that is not
 * followed by the escape of a low surrogate, or a low-surrogate escape on its own, yields a Java
 * string holding a lone surrogate. It is no character and has no UTF-8 encoding, so the driver writes
 * {@code ?} in its place. Without this check, a review would answer 200 echoing the value and an add
 * 201 with it in the body, while the row holds a different value.
 *
 * <p>With the check, either request is rejected before the service runs, as 400 APP0400 with an
 * {@code errors[]} entry on the property, like a value longer than its column, so nothing is stored
 * and no customer id is used up. The 5250 screen could key neither.
 *
 * <p><b>What it does not check.</b> Only NUL and unpaired surrogates are rejected; every other
 * character passes, other control characters, noncharacters such as U+FFFF and supplementary
 * characters (a high surrogate immediately followed by a low one) included. It adds no format rule:
 * ZIP and phone numbers stay free-form, as in the source. {@code null} passes so that an absent field
 * still reaches {@code service/CustomerValidator}, which reports a missing value as a business-rule
 * failure, and so that an absent {@code active} still defaults to {@code Y} on add.
 *
 * <p><b>Contract.</b> The problem's reason phrases a violation by this annotation's simple name,
 * {@code StorableText}, never by its message, so the default message is a fixed English phrase that
 * names no value. Unlike {@code @Pattern}, a custom constraint has no JSON Schema counterpart, so
 * springdoc adds nothing for it and the committed OpenAPI snapshot is unaffected.
 *
 * <p>Example:
 * <pre>{@code
 * record Body(@CodePointLength(max = 40) @StorableText String name) { }
 * // name = "AB" + NUL + "C"            -> 400 APP0400 "Request is not valid: name contains a
 * //                                       character that cannot be stored", errors[0].field = "name"
 * // name = "QA " + (char) 0xD800 + "X" -> the same 400: a high surrogate with no low one after it
 * // name = Character.toString(0x1F600) -> valid: one supplementary character, a surrogate pair
 * // name = null                        -> valid here; the business rules decide
 * }</pre>
 */
@Documented
@Constraint(validatedBy = StorableTextValidator.class)
@Target({FIELD, METHOD, PARAMETER, ANNOTATION_TYPE})
@Retention(RUNTIME)
public @interface StorableText {

    /**
     * The violation message; the API never shows it, because the problem's reason is derived from the
     * constraint name.
     *
     * @return a fixed phrase that quotes no value
     */
    String message() default "contains a character that cannot be stored";

    /**
     * The validation groups.
     *
     * @return the groups; none by default
     */
    Class<?>[] groups() default {};

    /**
     * The payload attached to a violation.
     *
     * @return the payload; none by default
     */
    Class<? extends Payload>[] payload() default {};
}
