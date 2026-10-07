// Change-stamp text for the customer detail form ("Last Change … by …").
//
// Replaces MTNCUSTR's FillScreenFields, which shows the stamp only when CHGUSER
// is neither '*SYSTEM*' nor blank, and builds the time part in SQL:
//
//   varchar_format(:CHGTIME, 'YYYY-Mon-DD') concat ' at ' concat
//   varchar_format(:CHGTIME, 'HH24:MI:SS')
//
// Otherwise indicator 61 stays off and MTNCUSTD hides "Last Change",
// SD_CHGTIME, "by" and SD_CHGUSER with DSPATR(ND). Here a hidden stamp is
// `null`, and the caller renders nothing for it.
//
// Differences from the 5250 screen, both intentional:
// - The time is shown in the browser's local time zone. The server stores and
//   sends `chgTime` as an instant (timestamptz, ISO-8601), where Db2 stored the
//   job's local time with no zone.
// - The full user is shown, up to the 18 characters of `chguser varchar(18)`,
//   where the DDS field SD_CHGUSER held 15.
//
// The text is assembled from the local Date getters and a fixed English month
// table, never from toLocaleString or Intl, so it reads the same in every
// browser locale, as varchar_format's 'Mon' token always yields an English
// three-letter month.
//
// Example:
//   formatChangeStamp('2026-10-05T14:03:09Z', 'sales')
//     → '2026-Oct-05 at 14:03:09 by sales' in a UTC browser
//   formatChangeStamp('2026-10-05T14:03:09Z', '*SYSTEM*') → null

/** Users whose rows carry no visible stamp: seed and generator loads stamp '*SYSTEM*'. */
const SYSTEM_USER = '*SYSTEM*';

/** English three-letter month names, as Db2 varchar_format's 'Mon' token yields. */
const MONTHS = [
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
] as const;

/** Zero-pads a non-negative integer to `width` digits. */
function pad(value: number, width: number): string {
  return String(value).padStart(width, '0');
}

/**
 * Four-digit year, as varchar_format's 'YYYY'. Years past 9999 keep all their
 * digits, and a year before year 0 keeps its sign ahead of the padded digits,
 * so no instant JavaScript can represent produces a malformed year.
 */
function formatYear(year: number): string {
  return year < 0 ? `-${pad(-year, 4)}` : pad(year, 4);
}

/**
 * Formats a customer's last-change stamp as `YYYY-Mon-DD at HH:mm:ss by USER`
 * in browser-local time, with a 24-hour clock.
 *
 * @param chgTime ISO-8601 instant from `CustomerResponse.chgTime`.
 * @param chgUser The user who made the last change, from `CustomerResponse.chgUser`.
 * @returns The stamp text, or `null` when the stamp is hidden: the user is
 *   blank or `*SYSTEM*`, or `chgTime` is not a parseable date.
 */
export function formatChangeStamp(chgTime: string, chgUser: string): string | null {
  // The values come from a JSON payload, so a missing member is guarded at
  // run time as well: `new Date(null)` would otherwise yield the 1970 epoch and
  // show a stamp the record never carried.
  const user = typeof chgUser === 'string' ? chgUser.trim() : '';
  if (user === '' || user === SYSTEM_USER) {
    return null;
  }
  if (typeof chgTime !== 'string') {
    return null;
  }

  const date = new Date(chgTime);
  if (Number.isNaN(date.getTime())) {
    return null;
  }

  // getMonth() is always 0..11, so the fallback is unreachable; it only
  // narrows the indexed read, which noUncheckedIndexedAccess types as
  // `string | undefined`.
  const month = MONTHS[date.getMonth()] ?? '';
  const day = pad(date.getDate(), 2);
  const hours = pad(date.getHours(), 2);
  const minutes = pad(date.getMinutes(), 2);
  const seconds = pad(date.getSeconds(), 2);

  return `${formatYear(date.getFullYear())}-${month}-${day} at ${hours}:${minutes}:${seconds} by ${user}`;
}
