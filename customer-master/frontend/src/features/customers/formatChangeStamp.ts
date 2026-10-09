// Change-stamp text for the customer detail form ("Last Change … by …").
//
// MTNCUSTR's FillScreenFields shows the stamp only when CHGUSER is neither
// '*SYSTEM*' nor blank, and builds its time with varchar_format 'YYYY-Mon-DD'
// concat ' at ' concat 'HH24:MI:SS' (5250_Subfile/MTNCUSTR.SQLRPGLE:355-358);
// otherwise MTNCUSTD hides it under indicator 61 with DSPATR(ND). Here a
// hidden stamp is `null`.
//
// Intentional differences from the 5250 screen:
// - Browser-local time: the server sends `chgTime` as an instant, where Db2
//   stored the job's local time with no zone.
// - The full user, up to the 18 characters of `chguser varchar(18)`, where
//   SD_CHGUSER held 15.

/** Users whose rows carry no visible stamp: seed and generator loads stamp '*SYSTEM*'. */
const SYSTEM_USER = '*SYSTEM*';

/**
 * Fixed English month table. The stamp is built from it and the local Date
 * getters, never toLocaleString or Intl, so it reads the same in every browser
 * locale, as varchar_format's 'Mon' always yields an English three-letter month.
 */
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
 * @param chgTime ISO-8601 instant from `CustomerResponse.chgTime`, or `null`
 *   when the record carries no change time, which the contract allows; a
 *   `null` time hides the stamp.
 * @param chgUser The user who made the last change, from `CustomerResponse.chgUser`.
 * @returns The stamp text, or `null` when the stamp is hidden: the user is
 *   blank or `*SYSTEM*`, or `chgTime` is `null` or not a parseable date.
 */
export function formatChangeStamp(chgTime: string | null, chgUser: string): string | null {
  // A `null` chgTime is the contract's own case and hides the stamp:
  // `new Date(null)` would otherwise yield the 1970 epoch and show a stamp the
  // record never carried. The typeof checks also guard a member missing from
  // the JSON payload at run time, which no static type rules out.
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
