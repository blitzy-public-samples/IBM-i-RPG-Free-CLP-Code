/**
 * Specs for `formatChangeStamp`, the "Last Change … by …" text of the customer
 * detail form.
 *
 * Source behaviour (MTNCUSTR FillScreenFields, MTNCUSTD record fields
 * SD_CHGTIME and SD_CHGUSER under indicator 61): the stamp is shown only when
 * CHGUSER is neither `*SYSTEM*` nor blank, and its time part is
 * `varchar_format(CHGTIME, 'YYYY-Mon-DD') concat ' at ' concat
 * varchar_format(CHGTIME, 'HH24:MI:SS')`. The target shows
 * `YYYY-Mon-DD at HH:mm:ss by USER` in browser-local time, and `null` where
 * the 5250 screen kept the stamp non-display.
 *
 * Time-zone independence. The host running Vitest may sit in any zone, so the
 * expected text is never a hard-coded wall-clock string for an instant: it is
 * built by {@link expected} from the local `Date` getters, with a month table
 * and padding of its own rather than the unit's. Only inputs without a zone
 * (parsed as local time) and the pinned-zone block, which sets `TZ` itself,
 * assert literal strings.
 */
import { describe, expect, it } from 'vitest';
import { formatChangeStamp } from './formatChangeStamp';

/**
 * English three-letter months, as Db2 varchar_format's 'Mon' token yields
 * them. Kept here rather than imported, so the oracle shares no table with the
 * unit under test.
 */
const MONTH_ABBREVIATIONS = [
  'Jan',
  'Feb',
  'Mar',
  'Apr',
  'May',
  'Jun',
  'Jul',
  'Aug',
  'Sep',
  'Oct',
  'Nov',
  'Dec',
];

/** Shape every visible stamp has, whatever the zone: `YYYY-Mon-DD at HH:mm:ss by <user>`. */
const STAMP_SHAPE = /^\d{4}-[A-Z][a-z]{2}-\d{2} at \d{2}:\d{2}:\d{2} by /;

/** Two-digit zero padding for a value in 0..99, by slicing rather than `padStart`. */
function twoDigits(value: number): string {
  return `0${value}`.slice(-2);
}

/**
 * Independent oracle: the stamp `formatChangeStamp(iso, user)` must return,
 * built from the local getters of `new Date(iso)`, so it holds in every host
 * time zone. `user` is the expected trimmed user.
 */
function expected(iso: string, user: string): string {
  const date = new Date(iso);
  const month = MONTH_ABBREVIATIONS[date.getMonth()];
  if (month === undefined || Number.isNaN(date.getTime())) {
    throw new Error(`Oracle given an unparseable date: ${iso}`);
  }
  const year = `000${date.getFullYear()}`.slice(-4);
  const day = twoDigits(date.getDate());
  const time = [date.getHours(), date.getMinutes(), date.getSeconds()].map(twoDigits).join(':');
  return `${year}-${month}-${day} at ${time} by ${user}`;
}

/** The month token of a visible stamp (`Oct` in `2026-Oct-05 at …`), or `undefined`. */
function monthToken(stamp: string | null): string | undefined {
  return stamp?.match(/^\d{4}-([A-Za-z]+)-/)?.[1];
}

/**
 * Runs `body` with the process time zone set to `timeZone`, then restores the
 * previous setting. Node re-reads `TZ` whenever `process.env.TZ` is assigned or
 * deleted, and Vitest's default `forks` pool gives each test file its own
 * process, so the change is confined to this file and undone before the next
 * test.
 */
function withTimeZone<T>(timeZone: string, body: () => T): T {
  const previous = process.env.TZ;
  process.env.TZ = timeZone;
  try {
    return body();
  } finally {
    if (previous === undefined) {
      delete process.env.TZ;
    } else {
      process.env.TZ = previous;
    }
  }
}

describe('formatChangeStamp', () => {
  describe('visible stamp', () => {
    it('formats an instant as YYYY-Mon-DD at HH:mm:ss by USER in local time', () => {
      const stamp = formatChangeStamp('2026-10-05T14:03:09Z', 'sales');

      expect(stamp).toBe(expected('2026-10-05T14:03:09Z', 'sales'));
      expect(stamp).toMatch(/^\d{4}-[A-Z][a-z]{2}-\d{2} at \d{2}:\d{2}:\d{2} by sales$/);
    });

    it('shows the wall-clock time of the browser zone, not UTC', () => {
      // A second oracle that avoids the local getters altogether: shift the
      // instant by the zone offset in force at that instant and read the
      // result with the UTC getters.
      const iso = '2026-10-05T14:03:09Z';
      const instant = Date.parse(iso);
      const wallClock = new Date(instant - new Date(instant).getTimezoneOffset() * 60_000);
      const month = MONTH_ABBREVIATIONS[wallClock.getUTCMonth()];
      const time = [wallClock.getUTCHours(), wallClock.getUTCMinutes(), wallClock.getUTCSeconds()]
        .map(twoDigits)
        .join(':');

      expect(formatChangeStamp(iso, 'sales')).toBe(
        `${wallClock.getUTCFullYear()}-${month}-${twoDigits(wallClock.getUTCDate())} at ${time} by sales`,
      );
    });

    // Mid-month at 12:00Z: every zone from UTC-12 to UTC+14 still reads the
    // 15th or the 16th of the same month, so the month token cannot roll over.
    it.each([
      { mm: '01', mon: 'Jan' },
      { mm: '02', mon: 'Feb' },
      { mm: '03', mon: 'Mar' },
      { mm: '04', mon: 'Apr' },
      { mm: '05', mon: 'May' },
      { mm: '06', mon: 'Jun' },
      { mm: '07', mon: 'Jul' },
      { mm: '08', mon: 'Aug' },
      { mm: '09', mon: 'Sep' },
      { mm: '10', mon: 'Oct' },
      { mm: '11', mon: 'Nov' },
      { mm: '12', mon: 'Dec' },
    ])('renders month $mm as the English abbreviation $mon', ({ mm, mon }) => {
      const iso = `2026-${mm}-15T12:00:00Z`;
      const stamp = formatChangeStamp(iso, 'sales');

      expect(monthToken(stamp)).toBe(mon);
      expect(stamp).toBe(expected(iso, 'sales'));
    });

    it('zero-pads the day and every time component', () => {
      // A date-time string without an offset is parsed as local time, so the
      // wall clock it names is the one the stamp must show in any zone.
      expect(formatChangeStamp('2026-01-02T03:04:05', 'maint')).toBe('2026-Jan-02 at 03:04:05 by maint');
    });

    it('uses a 24-hour clock', () => {
      const stamp = formatChangeStamp('2026-07-14T23:59:58', 'maint');

      expect(stamp).toBe('2026-Jul-14 at 23:59:58 by maint');
      expect(stamp).not.toMatch(/AM|PM/i);
    });

    it('writes the year with four digits, as varchar_format YYYY does', () => {
      const iso = '0999-06-15T12:00:00Z';
      const stamp = formatChangeStamp(iso, 'sales');

      expect(stamp).toBe(expected(iso, 'sales'));
      expect(stamp).toMatch(/^0999-Jun-1[56] at /);
    });

    it.each([
      { label: 'microseconds and Z, as the API sends it', iso: '2026-10-05T14:03:09.123456Z' },
      { label: 'microseconds and a numeric offset', iso: '2026-10-05T16:03:09.123456+02:00' },
      { label: 'a fraction just below the next second', iso: '2026-10-05T14:03:09.999999Z' },
    ])('reads a chgTime with $label, dropping the fraction', ({ iso }) => {
      // CustomerResponse.chgTime is a timestamptz(6) Instant serialized by
      // Jackson. HH24:MI:SS truncates the fraction, so .999999 stays at :09.
      const stamp = formatChangeStamp(iso, 'sales');

      expect(stamp).toBe(expected('2026-10-05T14:03:09Z', 'sales'));
    });
  });

  describe('user', () => {
    it('keeps the stored user, trimmed of padding', () => {
      const stamp = formatChangeStamp('2026-10-05T14:03:09Z', 'sales   ');

      expect(stamp).toBe(expected('2026-10-05T14:03:09Z', 'sales'));
      expect(stamp).toMatch(/ by sales$/);
    });

    it('keeps the case of the stored user', () => {
      expect(formatChangeStamp('2026-01-02T03:04:05', 'Sales.Rep')).toBe('2026-Jan-02 at 03:04:05 by Sales.Rep');
    });

    it('shows an 18-character user in full, past the 15 of SD_CHGUSER', () => {
      const user = 'account.manager-01';
      expect(user).toHaveLength(18);

      const stamp = formatChangeStamp('2026-10-05T14:03:09Z', user);

      expect(stamp).toBe(expected('2026-10-05T14:03:09Z', user));
      expect(stamp).toMatch(STAMP_SHAPE);
      expect(stamp?.endsWith(` by ${user}`)).toBe(true);
    });
  });

  describe('hidden stamp', () => {
    // MTNCUSTR shows the stamp only when CHGUSER <> '*SYSTEM*' and
    // CHGUSER <> ' '. RPG compares strings blank-padded, so a padded
    // '*SYSTEM*' and an all-blank user hide it as well.
    it.each([
      { label: "the seed and generator user '*SYSTEM*'", chgUser: '*SYSTEM*' },
      { label: "a blank-padded '*SYSTEM*'", chgUser: '*SYSTEM*   ' },
      { label: 'an empty user', chgUser: '' },
      { label: 'an all-blank user', chgUser: '   ' },
    ])('returns null for $label', ({ chgUser }) => {
      expect(formatChangeStamp('2026-10-05T14:03:09Z', chgUser)).toBeNull();
    });

    it.each([
      { label: 'an empty chgTime', chgTime: '' },
      { label: 'an unparseable chgTime', chgTime: 'not-a-date' },
      { label: 'a null chgTime', chgTime: null },
    ])('returns null for $label with a real user', ({ chgTime }) => {
      expect(formatChangeStamp(chgTime, 'sales')).toBeNull();
    });

    it("keeps a user that merely contains '*SYSTEM*' visible", () => {
      // Only the exact value hides the stamp; any other non-blank user shows it.
      expect(formatChangeStamp('2026-01-02T03:04:05', 'x*SYSTEM*')).toBe('2026-Jan-02 at 03:04:05 by x*SYSTEM*');
    });
  });

  describe('pinned time zones', () => {
    // Literal expectations for one instant, with the zone set explicitly, so
    // the conversion to local time is checked against known answers whatever
    // zone the host runs in.
    it.each([
      { timeZone: 'UTC', text: '2026-Oct-05 at 14:03:09' },
      { timeZone: 'America/New_York', text: '2026-Oct-05 at 10:03:09' },
      { timeZone: 'Asia/Kolkata', text: '2026-Oct-05 at 19:33:09' },
      { timeZone: 'Pacific/Kiritimati', text: '2026-Oct-06 at 04:03:09' },
      { timeZone: 'Pacific/Pago_Pago', text: '2026-Oct-05 at 03:03:09' },
    ])('shows 2026-10-05T14:03:09Z as $text in $timeZone', ({ timeZone, text }) => {
      const stamp = withTimeZone(timeZone, () => formatChangeStamp('2026-10-05T14:03:09Z', 'sales'));

      expect(stamp).toBe(`${text} by sales`);
    });
  });
});
