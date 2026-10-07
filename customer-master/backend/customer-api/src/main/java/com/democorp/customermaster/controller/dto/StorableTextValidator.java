package com.democorp.customermaster.controller.dto;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/**
 * Validates {@link StorableText}: a character sequence is valid when it is {@code null}, or contains
 * no U+0000 (NUL) and no unpaired surrogate.
 *
 * <p>The check scans UTF-16 code units once, left to right. NUL is a single code unit and never part
 * of a surrogate pair, so no code point can hide it or be mistaken for it. A high surrogate is valid
 * only when the next unit is a low surrogate, and the two are then taken together as one
 * supplementary character; a low surrogate reached on its own was not preceded by a high one. So a
 * high surrogate at the end, a high surrogate followed by anything but a low one, and a low surrogate
 * at the start or after any other unit are all invalid, as is a reversed pair (low, then high).
 *
 * <p>The validator keeps no state and is thread-safe; the validation provider may share one
 * instance across threads.
 */
public final class StorableTextValidator implements ConstraintValidator<StorableText, CharSequence> {

    /** The one character a PostgreSQL text column cannot store. */
    static final char NUL = '\0';

    /** Creates the validator; the validation provider instantiates it. */
    public StorableTextValidator() {
        // Stateless: nothing to initialize.
    }

    /**
     * Tells whether a value can be stored in a PostgreSQL text column as it is.
     *
     * @param value   the value; may be {@code null}
     * @param context the validation context; not used
     * @return {@code true} when {@code value} is {@code null}, or contains no NUL and every surrogate in
     *     it is half of a high-low pair
     */
    @Override
    public boolean isValid(CharSequence value, ConstraintValidatorContext context) {
        if (value == null) {
            return true;
        }
        int length = value.length();
        for (int i = 0; i < length; i++) {
            char unit = value.charAt(i);
            if (unit == NUL || Character.isLowSurrogate(unit)) {
                return false;
            }
            if (Character.isHighSurrogate(unit)) {
                if (i + 1 == length || !Character.isLowSurrogate(value.charAt(i + 1))) {
                    return false;
                }
                // A well-formed pair: skip its low surrogate, which is not unpaired.
                i++;
            }
        }
        return true;
    }
}
