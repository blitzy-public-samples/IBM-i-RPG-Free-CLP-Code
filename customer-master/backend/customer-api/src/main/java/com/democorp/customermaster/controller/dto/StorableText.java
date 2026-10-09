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
 * Rejects text that cannot be stored, shown, searched and sent to the address service exactly as sent:
 * a value containing an unpaired UTF-16 surrogate, a control character (general category Cc, U+0000
 * NUL, TAB, LF and CR included), a format character (category Cf, such as the bidirectional controls
 * and the zero-width characters), U+2028 LINE SEPARATOR, U+2029 PARAGRAPH SEPARATOR or a Unicode
 * noncharacter (U+FDD0–U+FDEF, U+FFFE, U+FFFF and the last two code points of every other plane).
 * {@link StorableTextValidator} lists each class. A {@code null} value is valid.
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
 * <p><b>Why control and format characters.</b> A 5250 input field could never hold them: each
 * MTNCUSTD field the nine properties replace held one line of keyed text, never a TAB, a line break,
 * a bell, a bidirectional control or a zero-width character. Two later uses of a value also break on
 * them:
 * <ul>
 *   <li><b>The address service.</b> A review sends {@code addr}, {@code city}, {@code state} and
 *       {@code zip} in the USPS {@code AddressValidateRequest}, an XML 1.0 document, whose
 *       {@code Char} production excludes U+0000–U+0008, U+000B, U+000C, U+000E–U+001F, surrogates,
 *       U+FFFE and U+FFFF. Without this check such a value would make the request malformed, and the
 *       review would answer 502 APP0502 "Address service is unavailable" on every retry, with no field
 *       marked.</li>
 *   <li><b>Display and search.</b> A bidirectional control such as U+202E RIGHT-TO-LEFT OVERRIDE
 *       reorders the text around it, and zero-width and other format characters are invisible, so the
 *       stored value would be shown as a different string from the one stored (CWE-451): a name
 *       stored as U+202E followed by {@code YNAPMOC EMCA} reads as {@code ACME COMPANY} in the results
 *       table, the Display dialog and the Customer picker, while a search for {@code ACME} finds
 *       nothing. A line break, NEL, U+2028 or U+2029 splits a one-line field.</li>
 * </ul>
 * The noncharacters are reserved for a process's internal use and are not text to interchange, and
 * U+FFFE and U+FFFF are no XML 1.0 {@code Char} either. Every value this check accepts is therefore
 * legal XML 1.0 character data.
 *
 * <p>With the check, every such request is rejected before the service runs, as 400 APP0400 with an
 * {@code errors[]} entry on the property, like a value longer than its column, so nothing is stored,
 * nothing is sent to the address service and no customer id is used up. Nothing is stripped or
 * replaced: the server changes a value only by trimming, so a value is either kept as sent or refused
 * with the field that holds it.
 *
 * <p><b>What it does not check.</b> Every other character passes: letters, combining marks,
 * punctuation and symbols, U+00A0 NO-BREAK SPACE, U+FFFD, private-use characters such as U+E000 and
 * supplementary characters (a high surrogate immediately followed by a low one) such as U+1F600. It
 * adds no format rule: ZIP and phone numbers stay free-form, as in the source. {@code null} passes so
 * that an absent field still reaches {@code service/CustomerValidator}, which reports a missing value
 * as a business-rule failure, and so that an absent {@code active} still defaults to {@code Y} on
 * add.
 *
 * <p><b>Contract.</b> The problem's reason phrases a violation by this annotation's simple name,
 * {@code StorableText}, never by its message, so the default message is a fixed English phrase that
 * names no value. Unlike {@code @Pattern}, a custom constraint has no JSON Schema counterpart, so
 * springdoc adds nothing for it and the committed OpenAPI snapshot is unaffected.
 *
 * <p>Example:
 * <pre>{@code
 * record Body(@CodePointLength(max = 40) @StorableText String name) { }
 * // name = "AB" + NUL + "C"               -> 400 APP0400 "Request is not valid: name contains a
 * //                                          character that cannot be stored", errors[0].field = "name"
 * // name = "QA " + (char) 0xD800 + "X"    -> the same 400: a high surrogate with no low one after it
 * // name = "1 A" + (char) 0x01 + "B RD"   -> the same 400: a control character, no XML 1.0 Char
 * // name = "ACME" + (char) 0x09 + "INC"   -> the same 400: TAB is a control character too
 * // name = (char) 0x202E + "YNAPMOC EMCA" -> the same 400: a bidirectional control (format, Cf)
 * // name = "AC" + (char) 0x200B + "ME"    -> the same 400: a zero-width space (format, Cf)
 * // name = "A" + (char) 0xFFFF            -> the same 400: a noncharacter
 * // name = Character.toString(0x1F600)    -> valid: one supplementary character, a surrogate pair
 * // name = "STRAßE N" + (char) 0xA0 + "1" -> valid: letters and a no-break space
 * // name = null                           -> valid here; the business rules decide
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
