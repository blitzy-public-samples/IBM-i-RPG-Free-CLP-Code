/**
 * Client half of the shared length-preserving uppercase rule (AAP 0.4.7): no
 * 5250 input field declares `CHECK(LC)`, so the workstation uppercased every
 * keyed character.
 *
 * Each code point takes its full uppercase mapping (`toUpperCase()` of that one
 * code point, locale-independent like the server's `Locale.ROOT`) only when
 * that mapping is a single code point; otherwise it is kept, so `ß` (mapping
 * `SS`) and U+1F80 (mapping U+1F08 U+0399) stay. An astral character is mapped
 * as one code point; an unpaired surrogate passes through. Whole-string
 * `value.toUpperCase()` (expands `ß` to `SS`, changing the length) and
 * `toLocaleUpperCase()` (Turkish `i` → `İ`) are deliberately not used.
 *
 * The code-point count and the UTF-16 `length` never change (no
 * single-code-point mapping crosses the BMP/astral boundary), which `maxLength`
 * and `FormField`'s caret restore rely on. Nothing is trimmed; trimming is the
 * server's job (`TextNormalizer.field` / `filter`). The server's
 * `TextNormalizer` (backend `domain/TextNormalizer.java`) applies the same
 * algorithm, each side with its own runtime's Unicode case data, so a code
 * point newer than the JDK's Unicode version may be uppercased here and kept by
 * the server. The server's rule leaves every output of this function
 * unchanged, so what the input shows is what is stored, the server changing it
 * only by trimming. Both test suites share one parity table.
 *
 * @example
 * upperField('straße');  // 'STRAßE'
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
  // `for…of` steps by code point: one per surrogate pair or unpaired surrogate.
  for (const codePoint of value) {
    const upper = codePoint.toUpperCase();
    result += Array.from(upper).length === 1 ? upper : codePoint;
  }
  return result;
}
