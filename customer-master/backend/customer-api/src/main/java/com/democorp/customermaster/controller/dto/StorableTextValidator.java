package com.democorp.customermaster.controller.dto;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/**
 * Validates {@link StorableText}: a character sequence is valid when it is {@code null} or contains
 * no U+0000 (NUL).
 *
 * <p>The check scans UTF-16 code units. NUL is a single code unit and never part of a surrogate
 * pair, so no code point can hide it or be mistaken for it.
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
     * Tells whether a value can be stored in a PostgreSQL text column.
     *
     * @param value   the value; may be {@code null}
     * @param context the validation context; not used
     * @return {@code true} when {@code value} is {@code null} or contains no NUL
     */
    @Override
    public boolean isValid(CharSequence value, ConstraintValidatorContext context) {
        if (value == null) {
            return true;
        }
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) == NUL) {
                return false;
            }
        }
        return true;
    }
}
