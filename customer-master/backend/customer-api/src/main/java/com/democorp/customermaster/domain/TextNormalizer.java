package com.democorp.customermaster.domain;

import java.util.Locale;

/**
 * The one server-side rule for normalizing keyed text: uppercase every value, and trim it the way the
 * source trims it.
 *
 * <p><b>Why uppercase.</b> No input field of the 5250 screens this application replaces declares
 * {@code CHECK(LC)}: not the search filters {@code SC_NAME}, {@code SC_CITY} and {@code SC_STATE} of
 * PMTCUSTD, not the nine customer fields of MTNCUSTD, and not the "Name Contains" filter of PMTSTATED.
 * The workstation therefore uppercased everything the user typed before the program saw it. This class
 * turns that terminal behaviour into a server rule, so an API client gets exactly what the screen gave.
 * Search depends on it: LIKE is case-sensitive, and stored data and filters are both uppercase.
 *
 * <p><b>Lowercase is deliberately not carried.</b> The USPS variant of MTNCUSTD allowed lowercase
 * ({@code CHECK(LC)}) on name, address, city and account manager. That allowance is dropped: lowercase
 * data would be unreachable by the uppercase prefix search, so every field is uppercased here, as in the
 * base 5250 variant.
 *
 * <p><b>The uppercase rule.</b> Each code point is replaced by its full uppercase mapping, computed as
 * {@code String.toUpperCase(Locale.ROOT)} of that single code point, when the mapping is exactly one code
 * point; otherwise the code point is kept unchanged. The code-point length of a value therefore never
 * changes, so a value that fits its column, or the 13-character search-filter cut, still fits after
 * normalization. Examples:
 * <ul>
 *   <li>{@code abc} becomes {@code ABC}; U+00E9 (e acute) becomes U+00C9 (E acute);</li>
 *   <li>U+01C6 (dz digraph with caron) becomes U+01C4, its uppercase form, not the titlecase U+01C5;</li>
 *   <li>U+00DF (sharp s) is kept, because its full mapping {@code SS} is two code points, so the
 *       six-character German word for street, spelt with a sharp s, stays six characters;</li>
 *   <li>U+1F80 (Greek alpha with psili and ypogegrammeni) is kept, because its full mapping
 *       U+1F08 U+0399 is two code points.</li>
 * </ul>
 * The browser applies the identical rule through {@code frontend/src/components/upperField.ts} as the
 * user types, and both test suites share one parity table, so a value the screen shows is a value the
 * server stores. Three shortcuts would break that parity and are never used:
 * {@code toUpperCase()} on the whole string (expands sharp s to {@code SS} and changes the length),
 * {@code Character.toUpperCase(int)} (the simple mapping, which turns U+1F80 into U+1F88), and the
 * default locale (a Turkish locale maps {@code i} to a dotted capital I).
 *
 * <p><b>Scope.</b> This class only normalizes. It checks no lengths: column sizes are enforced by the
 * request DTOs (400 APP0400), and because the rule preserves length they hold before and after this step.
 * Seed rows loaded by Flyway are not passed through it, so a mixed-case seed value becomes uppercase only
 * when it is saved through the API.
 *
 * <p>The class is stateless and thread-safe, and depends on the JDK alone.
 */
public final class TextNormalizer {

    /** Distance from an ASCII lowercase letter to its uppercase letter. */
    private static final int ASCII_CASE_OFFSET = 'a' - 'A';

    private TextNormalizer() {
        throw new AssertionError("TextNormalizer is a static utility and is never instantiated");
    }

    /**
     * Normalizes a customer data field: removes trailing whitespace, keeps leading characters as typed,
     * and uppercases the result.
     *
     * <p>Removing trailing whitespace gives the parity of a blank-padded {@code CHAR} column, whose
     * trailing blanks carry no meaning; the target stores {@code varchar} without them. Leading blanks
     * are kept, as a 5250 field kept them. Whitespace is what {@link String#stripTrailing()} removes
     * ({@link Character#isWhitespace(int)}), which includes tabs and line breaks but not the no-break
     * space.
     *
     * <p>Applied to all nine customer fields on review, add and update, and to generator output.
     * <pre>{@code
     * TextNormalizer.field("  acme corp  ")  // "  ACME CORP"
     * TextNormalizer.field("   ")            // ""
     * TextNormalizer.field(null)             // ""
     * }</pre>
     *
     * @param s the value as received; may be {@code null}
     * @return the normalized value, never {@code null}; {@code ""} for {@code null} or blank input
     */
    public static String field(String s) {
        if (s == null) {
            return "";
        }
        return upper(s.stripTrailing());
    }

    /**
     * Normalizes a search or picker filter: removes leading and trailing whitespace and uppercases the
     * result.
     *
     * <p>Trimming both ends matches {@code %trim(SC_NAME)} and {@code %trim(SC_CITY)}, which the customer
     * search applies before appending its wildcard. Whitespace is what {@link String#strip()} removes
     * ({@link Character#isWhitespace(int)}).
     *
     * <p>Applied to the customer search filters {@code name}, {@code city} and {@code state}, and to the
     * state picker's {@code nameContains}.
     * <pre>{@code
     * TextNormalizer.filter("  nibh ")  // "NIBH"
     * TextNormalizer.filter(null)       // ""
     * }</pre>
     *
     * @param s the filter as received; may be {@code null}
     * @return the normalized filter, never {@code null}; {@code ""} for {@code null} or blank input
     */
    public static String filter(String s) {
        if (s == null) {
            return "";
        }
        return upper(s.strip());
    }

    /**
     * Applies the shared, length-preserving uppercase rule described on the class, without trimming.
     *
     * <p>For callers that must uppercase a value whose blanks are already settled. When no code point
     * changes, the argument itself is returned, with no result buffer or copy built, which keeps bulk
     * callers such as the generator cheap on already-uppercase data. An all-ASCII value is examined
     * without any allocation, while each non-ASCII code point still creates a short-lived string as its
     * mapping is computed.
     *
     * @param s the value; may be {@code null}
     * @return the uppercased value with the same code-point length, never {@code null}; {@code ""} for
     *         {@code null}
     */
    public static String upper(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        final int length = s.length();
        StringBuilder out = null;
        int index = 0;
        while (index < length) {
            final int cp = s.codePointAt(index);
            final int mapped = upperCodePoint(cp);
            if (out == null && mapped != cp) {
                // First change: copy the unchanged prefix. The result never needs more room than the
                // input, because a code point is only ever replaced by one that fits the same count.
                out = new StringBuilder(length);
                out.append(s, 0, index);
            }
            if (out != null) {
                out.appendCodePoint(mapped);
            }
            index += Character.charCount(cp);
        }
        return out == null ? s : out.toString();
    }

    /**
     * Maps one code point by the shared rule: its full uppercase mapping under {@link Locale#ROOT} when
     * that mapping is a single code point, otherwise the code point itself.
     *
     * <p>ASCII is resolved directly, because for ASCII the rule yields exactly {@code a..z} to
     * {@code A..Z} and every other character unchanged; this avoids a per-character allocation on the
     * generator's bulk path. Every other code point, including an unpaired surrogate, goes through the
     * full-mapping computation, which leaves an unpaired surrogate unchanged.
     *
     * @param cp the code point
     * @return the mapped code point
     */
    private static int upperCodePoint(int cp) {
        if (cp < 0x80) {
            return (cp >= 'a' && cp <= 'z') ? cp - ASCII_CASE_OFFSET : cp;
        }
        final String up = new String(Character.toChars(cp)).toUpperCase(Locale.ROOT);
        return up.codePointCount(0, up.length()) == 1 ? up.codePointAt(0) : cp;
    }
}
