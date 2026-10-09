/**
 * Specifies `upperField`, the client half of the shared uppercase rule
 * (AAP 0.4.7).
 *
 * Cross-folder parity. The backend `domain/TextNormalizerTest` checks
 * `TextNormalizer` (the same algorithm, run on the JDK's Unicode case data)
 * against the same parity table, case for case; the server additionally trims,
 * which `upperField` never does. Change a row here only together with that
 * suite.
 *
 * Encoding. Non-ASCII literals are written as `\uXXXX` escapes, most with their
 * Unicode name in a comment, so no editor or formatter can normalise them into
 * a different code-point sequence.
 */
import { describe, expect, it } from 'vitest';
import { upperField } from './upperField';

interface ParityCase {
  readonly name: string;
  readonly input: string;
  readonly expected: string;
}

/**
 * The shared parity table, identical to `TextNormalizerTest.parityTable()`.
 * None of the inputs carries a blank, so the server's trimming cannot make
 * the two suites diverge on these rows.
 */
const PARITY_TABLE: readonly ParityCase[] = [
  { name: 'abc becomes ABC', input: 'abc', expected: 'ABC' },
  // U+00DF LATIN SMALL LETTER SHARP S: its full mapping "SS" is two code
  // points, so it is kept.
  { name: 'U+00DF sharp s is kept', input: '\u00df', expected: '\u00df' },
  // U+00E9 LATIN SMALL LETTER E WITH ACUTE becomes U+00C9 LATIN CAPITAL
  // LETTER E WITH ACUTE.
  { name: 'U+00E9 e acute becomes U+00C9', input: '\u00e9', expected: '\u00c9' },
  // U+01C6 LATIN SMALL LETTER DZ WITH CARON becomes U+01C4, its uppercase
  // form, not the titlecase U+01C5.
  { name: 'U+01C6 dz with caron becomes U+01C4', input: '\u01c6', expected: '\u01c4' },
  // U+1F80 GREEK SMALL LETTER ALPHA WITH PSILI AND YPOGEGRAMMENI: its full
  // mapping is the two code points U+1F08 U+0399, so it is kept.
  { name: 'U+1F80 alpha with psili and ypogegrammeni is kept', input: '\u1f80', expected: '\u1f80' },
];

function codePointCount(value: string): number {
  return Array.from(value).length;
}

/**
 * Asserts the expected value, an unchanged UTF-16 length and an unchanged
 * code-point count: the three properties both parity suites check per row.
 */
function expectLengthPreserving(actual: string, input: string, expected: string): void {
  expect(actual).toBe(expected);
  expect(actual.length).toBe(input.length);
  expect(codePointCount(actual)).toBe(codePointCount(input));
}

describe('upperField', () => {
  describe('parity table shared with TextNormalizerTest', () => {
    it.each(PARITY_TABLE)('$name', ({ input, expected }) => {
      expectLengthPreserving(upperField(input), input, expected);
    });

    it.each(PARITY_TABLE)('$name, and the expected value is already normalized', ({ expected }) => {
      expect(upperField(expected)).toBe(expected);
    });

    it('does not use whole-string toUpperCase, which expands sharp s to SS', () => {
      // Negative control: the plain full mapping changes the length the 5250
      // field and the browser input both preserve.
      expect('\u00df'.toUpperCase()).toBe('SS');
      expect(upperField('\u00df')).not.toBe('SS');
    });

    it('keeps U+1F80 rather than its two-code-point full mapping U+1F08 U+0399', () => {
      // U+1F08 GREEK CAPITAL LETTER ALPHA WITH PSILI, U+0399 GREEK CAPITAL
      // LETTER IOTA.
      expect('\u1f80'.toUpperCase()).toBe('\u1f08\u0399');
      expect(upperField('\u1f80')).toBe('\u1f80');
    });

    it('maps U+01C6 to uppercase U+01C4, never to titlecase U+01C5', () => {
      // U+01C5 LATIN CAPITAL LETTER D WITH SMALL LETTER Z WITH CARON.
      expect(upperField('\u01c6')).not.toBe('\u01c5');
    });
  });

  describe('length preservation', () => {
    it('keeps a 40-character mixed-case field value at 40 characters', () => {
      // The full width of SD_NAME, SD_ADDR and SD_ACCTMGR (40) in MTNCUSTD.
      // "Strasse" is spelt with U+00DF LATIN SMALL LETTER SHARP S.
      const input = 'Stra\u00dfe ' + 'abcdefghij'.repeat(3) + 'xyz';
      expect(input.length).toBe(40);

      const output = upperField(input);

      expect(output).toBe('STRA\u00dfE ' + 'ABCDEFGHIJ'.repeat(3) + 'XYZ');
      expect(output.length).toBe(40);
      expect(Array.from(output).length).toBe(40);
      expect(output).toContain('\u00df');
    });

    it('keeps the TextNormalizerTest 40-character value at 40 characters', () => {
      // The same input as TextNormalizerTest: U+00DF sharp s, U+00E9 e acute,
      // U+01C6 dz with caron and U+1F80 alpha with psili and ypogegrammeni
      // among ASCII words.
      const input = 'Stra\u00dfe \u00e9 \u01c6 \u1f80 Mixed Case Company Name abc';
      expect(input.length).toBe(40);
      expect(codePointCount(input)).toBe(40);

      const output = upperField(input);

      expectLengthPreserving(output, input, 'STRA\u00dfE \u00c9 \u01c4 \u1f80 MIXED CASE COMPANY NAME ABC');
      // Contrast: plain uppercasing expands U+00DF and U+1F80 and would
      // overflow the 40-character column.
      expect(input.toUpperCase()).toHaveLength(42);
    });

    it('keeps a 13-character filter at 13 characters', () => {
      // The full width of SC_NAME and SC_CITY (13) in PMTCUSTD; the seed name
      // NIBH L'LOR COMPANY cut to the filter width.
      const input = "nibh l'lor co";
      expect(input.length).toBe(13);

      expectLengthPreserving(upperField(input), input, "NIBH L'LOR CO");
    });

    it('keeps the TextNormalizerTest 13-character filter at 13 characters', () => {
      // U+00DF sharp s and U+1F80 alpha with psili and ypogegrammeni.
      const input = 'stra\u00dfe \u1f80 city';
      expect(input.length).toBe(13);
      expect(codePointCount(input)).toBe(13);

      expectLengthPreserving(upperField(input), input, 'STRA\u00dfE \u1f80 CITY');
      // Contrast: plain uppercasing would give 15 characters and push the
      // filter past the 13-character cut.
      expect(input.toUpperCase()).toHaveLength(15);
    });

    it('turns the FormField example stra\u00dfe into STRA\u00dfE with six characters', () => {
      expectLengthPreserving(upperField('stra\u00dfe'), 'stra\u00dfe', 'STRA\u00dfE');
    });

    it('maps a supplementary code point as one code point and keeps its surrogate pair', () => {
      // U+10428 DESERET SMALL LETTER LONG I (a surrogate pair) has the single
      // code point uppercase U+10400 DESERET CAPITAL LETTER LONG I.
      const output = upperField('a\ud801\udc28b');

      expect(output).toBe('A\ud801\udc00B');
      expect(output.length).toBe(4);
      expect(codePointCount(output)).toBe(3);
    });

    it('passes an unpaired surrogate through unchanged', () => {
      expect(upperField('ab\ud800')).toBe('AB\ud800');
      expect(upperField('\ud800cd')).toBe('\ud800CD');
    });
  });

  describe('blanks are never trimmed', () => {
    it.each([
      { name: 'leading and trailing blanks', input: '  abc  ', expected: '  ABC  ' },
      { name: 'a single blank', input: ' ', expected: ' ' },
      { name: 'the empty string', input: '', expected: '' },
      { name: 'inner blanks', input: 'a  b ', expected: 'A  B ' },
      { name: 'tabs', input: '\tabc\t', expected: '\tABC\t' },
    ])('keeps $name', ({ input, expected }) => {
      expectLengthPreserving(upperField(input), input, expected);
    });
  });

  describe('characters without case', () => {
    it.each([
      // The backslashes of the seed name URNA \NUNC\ COMPANY and the
      // apostrophe of NIBH L'LOR COMPANY, among digits and an ampersand.
      { name: 'backslashes, digits, ampersand and apostrophe', input: "\\NUNC\\ 123 & '" },
      { name: 'the TextNormalizerTest punctuation value', input: "O'BRIEN & SONS, \\NUNC\\ 123" },
    ])('leaves $name unchanged', ({ input }) => {
      expectLengthPreserving(upperField(input), input, input);
    });

    it.each([
      { input: 'urna \\nunc\\ company', expected: 'URNA \\NUNC\\ COMPANY' },
      { input: "nibh l'lor company", expected: "NIBH L'LOR COMPANY" },
    ])('uppercases only the letters of $expected', ({ input, expected }) => {
      expectLengthPreserving(upperField(input), input, expected);
    });

    it('never yields the Turkish dotted capital I', () => {
      // toUpperCase is locale-independent; U+0130 LATIN CAPITAL LETTER I WITH
      // DOT ABOVE would appear only under a Turkish locale mapping.
      const output = upperField('istanbul');

      expect(output).toBe('ISTANBUL');
      expect(output).not.toContain('\u0130');
    });
  });
});
