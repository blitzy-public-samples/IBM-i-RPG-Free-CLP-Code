/**
 * Applies the shared, length-preserving uppercase rule to a value as the user
 * types it (the client half of the text-normalization rule, AAP 0.4.7).
 *
 * Why: no input field of the 5250 screens this application replaces declares
 * `CHECK(LC)` — not the PMTCUSTD search filters (`SC_NAME`, `SC_CITY`,
 * `SC_STATE`), not the nine MTNCUSTD customer fields, and not the PMTSTATED
 * "Name Contains" filter — so the workstation uppercased every keyed character
 * before the program saw it. The server applies the identical rule in
 * `TextNormalizer` (backend `domain/TextNormalizer.java`), and both test
 * suites share one parity table. What the input shows is therefore what the
 * server stores; the server changes such a value only by trimming.
 *
 * The rule: each code point is replaced by its full uppercase mapping
 * (`String.prototype.toUpperCase` of that one code point, which is
 * locale-independent like the server's `Locale.ROOT`) when the mapping is
 * exactly one code point; otherwise the code point is kept unchanged.
 * - `abc` → `ABC`, `é` → `É`, `ǆ` (U+01C6) → `Ǆ` (U+01C4, not titlecase U+01C5);
 * - `ß` is kept, because its full mapping `SS` is two code points, so
 *   `straße` → `STRAßE` stays six characters;
 * - `ᾀ` (U+1F80) is kept, because its full mapping U+1F08 U+0399 is two code
 *   points;
 * - an astral character is mapped as one code point and keeps its surrogate
 *   pair; an unpaired surrogate passes through unchanged.
 *
 * Guarantees callers rely on:
 * - The code-point count never changes, and no single-code-point uppercase
 *   mapping crosses the BMP/astral boundary, so the UTF-16 `length` — what
 *   `maxLength` and `selectionStart` count — never changes either. `FormField`
 *   restores the caret at the same offset on that basis.
 * - Nothing is trimmed and no blank is touched: leading, trailing and inner
 *   spaces are kept exactly. Trimming is the server's job
 *   (`TextNormalizer.field` / `filter`).
 *
 * Deliberately not used, because each breaks parity with the server:
 * `value.toUpperCase()` on the whole string (expands `ß` to `SS` and changes
 * the length) and `toLocaleUpperCase()` (a Turkish locale maps `i` to `İ`).
 *
 * @example
 * upperField('straße');  // 'STRAßE'
 * upperField('  ab ');   // '  AB '
 *
 * @param value The input's current value.
 * @returns The uppercased value, with the same length in code points and in
 *   UTF-16 units as `value`.
 */
export function upperField(value: string): string {
  if (value === '') {
    return value;
  }

  let result = '';
  // `for…of` iterates a string by code point, so a surrogate pair is one
  // iteration and an unpaired surrogate is yielded on its own.
  for (const codePoint of value) {
    const upper = codePoint.toUpperCase();
    // Keep the mapping only when it is itself a single code point.
    result += Array.from(upper).length === 1 ? upper : codePoint;
  }
  return result;
}
