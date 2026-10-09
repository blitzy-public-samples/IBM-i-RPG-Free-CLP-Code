package com.democorp.customermaster.controller.dto;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/**
 * Validates {@link StorableText}: a character sequence is valid when it is {@code null}, or when none
 * of its code points is unstorable. A code point is unstorable when it is
 * <ul>
 *   <li>a surrogate that is not half of a high-low pair;</li>
 *   <li>a control character, general category Cc: U+0000–U+001F (NUL, TAB, LF and CR included) and
 *       U+007F–U+009F (DEL and the C1 controls, such as U+0085 NEXT LINE);</li>
 *   <li>a format character, general category Cf: among others the bidirectional controls U+061C,
 *       U+200E, U+200F, U+202A–U+202E and U+2066–U+2069, the zero-width characters U+200B–U+200D,
 *       U+2060–U+2064 and U+FEFF, the soft hyphen U+00AD and the supplementary tag characters
 *       U+E0001 and U+E0020–U+E007F;</li>
 *   <li>U+2028 LINE SEPARATOR or U+2029 PARAGRAPH SEPARATOR (categories Zl and Zp);</li>
 *   <li>a noncharacter: U+FDD0–U+FDEF, and every code point whose low 16 bits are FFFE or FFFF
 *       (U+FFFE, U+FFFF, U+1FFFE, … U+10FFFF). Java reports these as unassigned, so they are tested
 *       by value rather than by category.</li>
 * </ul>
 * Every other code point is storable: letters, combining marks, digits, punctuation and symbols
 * ({@code ß}, {@code É}, {@code \}, the apostrophe), the spaces U+0020 and U+00A0, U+FFFD, private-use
 * characters such as U+E000, and supplementary characters such as U+1F600 and U+10FFFD. A valid value
 * therefore consists of XML 1.0 {@code Char}s only. {@link StorableText} gives the reason for each
 * class.
 *
 * <p>The check scans the sequence once, left to right, by code point: a high surrogate followed by a
 * low one is read as the one supplementary character they encode, so a supplementary format character
 * such as U+E0001 is judged by its own category and never by its UTF-16 units. Any other surrogate
 * unit is read on its own and is unpaired: a high surrogate at the end or before anything but a low
 * one, and a low surrogate at the start or after anything but a high one, a reversed pair (low, then
 * high) included. NUL is a single code unit and never part of a pair, so no code point can hide it.
 *
 * <p>The validator keeps no state and is thread-safe; the validation provider may share one
 * instance across threads.
 */
public final class StorableTextValidator implements ConstraintValidator<StorableText, CharSequence> {

    /** First code point of the noncharacter block U+FDD0–U+FDEF. */
    private static final int FIRST_BLOCK_NONCHARACTER = 0xFDD0;

    /** Last code point of the noncharacter block U+FDD0–U+FDEF. */
    private static final int LAST_BLOCK_NONCHARACTER = 0xFDEF;

    /**
     * Mask that keeps bits 1–15 of a code point. A code point whose low 16 bits are FFFE or FFFF, the
     * last two of each plane, has exactly these bits set, whatever its plane and its lowest bit.
     */
    private static final int PLANE_END_NONCHARACTERS = 0xFFFE;

    /** Creates the validator; the validation provider instantiates it. */
    public StorableTextValidator() {
        // Stateless: nothing to initialize.
    }

    /**
     * Tells whether a value can be stored, displayed, searched and sent to the address service exactly
     * as it is.
     *
     * @param value   the value; may be {@code null}
     * @param context the validation context; not used
     * @return {@code true} when {@code value} is {@code null}, or holds no unpaired surrogate, control
     *     character, format character, line or paragraph separator and noncharacter
     */
    @Override
    public boolean isValid(CharSequence value, ConstraintValidatorContext context) {
        if (value == null) {
            return true;
        }
        int length = value.length();
        int index = 0;
        while (index < length) {
            // A high-low pair yields its supplementary code point; any other surrogate yields itself.
            int codePoint = Character.codePointAt(value, index);
            if (!isStorable(codePoint)) {
                return false;
            }
            index += Character.charCount(codePoint);
        }
        return true;
    }

    /**
     * Tells whether one code point, as {@link #isValid isValid} reads it, may appear in a value.
     *
     * @param codePoint the code point; a surrogate here is one not paired in the value
     * @return {@code false} for a surrogate, a noncharacter, and a character of general category Cc,
     *     Cf, Zl or Zp; {@code true} otherwise
     */
    private static boolean isStorable(int codePoint) {
        if (codePoint >= Character.MIN_SURROGATE && codePoint <= Character.MAX_SURROGATE) {
            return false;
        }
        if ((codePoint >= FIRST_BLOCK_NONCHARACTER && codePoint <= LAST_BLOCK_NONCHARACTER)
                || (codePoint & PLANE_END_NONCHARACTERS) == PLANE_END_NONCHARACTERS) {
            return false;
        }
        return switch (Character.getType(codePoint)) {
            case Character.CONTROL, Character.FORMAT, Character.LINE_SEPARATOR,
                    Character.PARAGRAPH_SEPARATOR -> false;
            default -> true;
        };
    }
}
