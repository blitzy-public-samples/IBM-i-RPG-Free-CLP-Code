/**
 * Edit with confirmation (F-002, UC-03): change one customer through the search list's option 2,
 * with field validation, the confirmation pass and the last-change stamp.
 *
 * What it replays. MTNCUSTR called with function code `E` from PMTCUSTR option 2
 * [5250_Subfile/PMTCUSTR.SQLRPGLE:447-466], [5250_Subfile/MTNCUSTR.SQLRPGLE:195-247]:
 * - ReadRecd and FillScreenFields fill the change window; the cursor starts on Name.
 * - Enter runs EditUpdData. A blank Name fails rule 2 with DEM0502 "Name: Must not be blank",
 *   the field shown in reverse image with the cursor on it [5250_Subfile/MTNCUSTR.SQLRPGLE:455-464].
 * - A passed review protects every field and shows DEM0000 "Press Enter to update. F12 to Cancel."
 *   [5250_Subfile/MTNCUSTR.SQLRPGLE:221-226], [5250_Subfile/CRTMSGF.CLLE:12-13].
 * - F12 (or F5) at the confirmation loops back to ReadRecd: the stored record is shown again and
 *   the entries are discarded. This is a preserved source defect, asserted as the source behaves
 *   [5250_Subfile/MTNCUSTR.SQLRPGLE:226-247].
 * - Enter at the confirmation runs UpdateRecd and closes the window; PMTCUSTR re-reads the row
 *   into the subfile, so the list row changes in place with no new search
 *   [5250_Subfile/PMTCUSTR.SQLRPGLE:454-457].
 * - The update stamps ChgTime and ChgUser; MTNCUSTD shows them as "Last Change … by …"
 *   [5250_Subfile/MTNCUSTR.SQLRPGLE:340-363,576-591], [5250_Subfile/MTNCUSTD.DSPF:125-133]. The
 *   target stamps the authenticated principal (the Sales user) and bumps the row version.
 *
 * Ground rules this spec follows:
 * - It edits only the customer it creates through `createCustomer` (same-origin
 *   `POST /api/customers`), so it never depends on or changes a seed row, and every run is
 *   independent because `uniqueName` draws a fresh name.
 * - It searches with the 11-character `filter` of `uniqueName`, never the full name: a full
 *   13-character entry carries no wildcard and finds nothing (preserved PMTCUSTR defect).
 * - Credentials live in page memory only, so after sign-in it moves on by keys and fills alone,
 *   never by `page.goto`.
 * - Toasts clear on every click and command key, so each message is asserted right after the key
 *   that raised it.
 * - Enter is a command only on a text input, an option field or the scope's container; the
 *   confirmation panel focuses its own container on mount, which is where the commit Enter lands.
 */
import { createCustomer, expect, getCustomer, newCustomerFields, test, toasts, uniqueName, USERS } from '../fixtures/auth';
import type { CustomerFields, CustomerResponse } from '../fixtures/auth';

// ---------------------------------------------------------------------------
// Catalog texts (messages.properties, AAP 0.7.3), compared exactly
// ---------------------------------------------------------------------------

/** DEM0000, the notice of a passed EDIT review: the edit confirmation prompt. */
const DEM0000 = 'Press Enter to update. F12 to Cancel.';

/** DEM0502 `{0}: Must not be blank` with the Name rule's label argument. */
const DEM0502_NAME = 'Name: Must not be blank';

// ---------------------------------------------------------------------------
// Test data and screen texts
// ---------------------------------------------------------------------------

/** The city the customer is created with, and the one the reload at the confirmation brings back. */
const ORIGINAL_CITY = 'SPRINGFIELD';

/** The city the edit changes it to. */
const CHANGED_CITY = 'SHELBYVILLE';

/** The detail window in any of its three functions (MTNCUSTR H2TextD, H2TextE, H2TextA). */
const DETAIL_DIALOG_NAME = /Displaying Customer|Change Customer|Add Customer/;

/** English three-letter months, as Db2 `varchar_format(…, 'Mon')` and the stamp use them. */
const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'] as const;

/** The browser-local calendar and clock fields of one instant, as the SPA's `Date` getters read them. */
type LocalDateTime = {
  year: number;
  month: number;
  day: number;
  hours: number;
  minutes: number;
  seconds: number;
};

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

/** Escapes every regular-expression metacharacter, so a configured username matches literally. */
function escapeRegExp(text: string): string {
  return text.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

/**
 * The shape of the stamp line for `user`: `Last Change YYYY-Mon-DD at HH:mm:ss by USER`, the
 * MTNCUSTR `varchar_format(:CHGTIME,'YYYY-Mon-DD') concat ' at ' concat
 * varchar_format(:CHGTIME,'HH24:MI:SS')` text followed by the user.
 */
function stampPattern(user: string): RegExp {
  return new RegExp(
    `^Last Change \\d{4}-(?:${MONTHS.join('|')})-\\d{2} at \\d{2}:\\d{2}:\\d{2} by ${escapeRegExp(user)}$`,
  );
}

/** Zero-pads a non-negative integer to `width` digits. */
function pad(value: number, width: number): string {
  return String(value).padStart(width, '0');
}

/**
 * The exact stamp line for a change made by `user` at the browser-local time `at`.
 *
 * @throws Error when the month index lies outside 0..11, which no `Date` produces
 */
function expectedStamp(at: LocalDateTime, user: string): string {
  const month = MONTHS[at.month];
  if (month === undefined) {
    throw new Error(`expectedStamp: month index ${at.month} is outside 0..11.`);
  }
  const date = `${pad(at.year, 4)}-${month}-${pad(at.day, 2)}`;
  const time = `${pad(at.hours, 2)}:${pad(at.minutes, 2)}:${pad(at.seconds, 2)}`;
  return `Last Change ${date} at ${time} by ${user}`;
}

// ---------------------------------------------------------------------------
// The flow
// ---------------------------------------------------------------------------

test.use({ startPath: '/customers' });

test('edit with confirmation: blank Name rejected, F12 at the confirmation reloads, Enter saves in place', async ({
  asMaintenance: page,
  api,
}) => {
  const { name, filter } = uniqueName('EDIT');
  const fields: CustomerFields = newCustomerFields({ name, city: ORIGINAL_CITY });
  const maintenanceUser = USERS.maintenance.username;
  const { status, alert } = toasts(page);

  const nameFilter = page.getByLabel('Name starts with:', { exact: true });
  const resultRows = page.getByRole('table', { name: 'Customers' }).locator('tbody tr');
  const optionInput = page.getByLabel(`Option for ${name}`, { exact: true });

  const detail = page.getByRole('dialog', { name: DETAIL_DIALOG_NAME });
  const nameInput = detail.getByLabel('Name', { exact: true });
  const cityInput = detail.getByLabel('City', { exact: true });
  const confirmation = detail.getByRole('group', { name: 'Confirm customer' });
  const stampLine = detail.getByText(/^Last Change /);

  // Every list request the page sends (GET /api/customers with the criteria). The list must be
  // loaded once, by the search, and never again: a saved edit updates its row in place.
  const searchRequests: string[] = [];
  page.on('request', (request) => {
    if (request.method() !== 'GET') {
      return;
    }
    const url = new URL(request.url());
    if (url.pathname === '/api/customers') {
      searchRequests.push(url.search);
    }
  });

  const created: CustomerResponse = await test.step('create the customer to edit through the API', async () => {
    const customer = await createCustomer(api, fields);
    expect(customer).toMatchObject(fields);
    return customer;
  });

  await test.step('option 2 opens the change window on the stored record', async () => {
    // Maintenance waits for Enter before it loads anything (NewSearchCriteria at start).
    await expect(nameFilter).toBeFocused();
    await expect(resultRows).toHaveCount(0);
    expect(searchRequests).toHaveLength(0);

    await nameFilter.fill(filter);
    await page.keyboard.press('Enter');
    await expect(resultRows).toHaveCount(1);
    await expect(resultRows.first()).toContainText(name);
    expect(searchRequests).toHaveLength(1);

    await optionInput.fill('2');
    await page.keyboard.press('Enter');

    await expect(detail).toBeVisible();
    await expect(detail).toHaveAccessibleName(/Change Customer/);
    await expect(detail.getByLabel('Customer Id', { exact: true })).toHaveValue(created.custId);
    await expect(nameInput).toHaveValue(name);
    await expect(cityInput).toHaveValue(ORIGINAL_CITY);
    await expect(nameInput).toBeEditable();
    await expect(nameInput).toBeFocused();
    // The add through the API stamped the Sales user, so the stamp line is shown.
    await expect(stampLine).toHaveText(stampPattern(maintenanceUser));
  });

  await test.step('a blank Name is rejected with DEM0502 on the Name field', async () => {
    await nameInput.fill('');
    await page.keyboard.press('Enter');

    await expect(alert).toHaveText(DEM0502_NAME);
    await expect(status).toHaveText('');
    await expect(nameInput).toHaveAttribute('aria-invalid', 'true');
    await expect(nameInput).toBeFocused();
    await expect(nameInput).toBeEditable();
  });

  await test.step('a valid change passes review and shows the DEM0000 confirmation', async () => {
    await nameInput.fill(name);
    await cityInput.fill(CHANGED_CITY);
    await page.keyboard.press('Enter');

    await expect(status).toHaveText(DEM0000);
    await expect(alert).toHaveText('');
    await expect(confirmation).toBeFocused();
    await expect(cityInput).toHaveValue(CHANGED_CITY);
    await expect(cityInput).not.toBeEditable();
    await expect(nameInput).toHaveValue(name);
    await expect(nameInput).not.toBeEditable();
  });

  await test.step('F12 at the confirmation reloads the stored record and discards the entries', async () => {
    await page.keyboard.press('F12');

    await expect(detail).toBeVisible();
    await expect(detail).toHaveAccessibleName(/Change Customer/);
    await expect(cityInput).toBeEditable();
    await expect(cityInput).toHaveValue(ORIGINAL_CITY);
    await expect(nameInput).toHaveValue(name);
    await expect(nameInput).toBeFocused();
    await expect(status).toHaveText('');
    await expect(alert).toHaveText('');

    // Review writes nothing: the record is still the one created, at version 0.
    const unchanged = await getCustomer(api, created.custId);
    expect(unchanged).toEqual(created);
  });

  await test.step('Enter at the confirmation saves, closes the window and updates the row in place', async () => {
    await cityInput.fill(CHANGED_CITY);
    await page.keyboard.press('Enter');
    await expect(status).toHaveText(DEM0000);
    await expect(cityInput).toHaveValue(CHANGED_CITY);
    await expect(cityInput).not.toBeEditable();
    await expect(confirmation).toBeFocused();

    await page.keyboard.press('Enter');

    await expect(detail).toBeHidden();
    await expect(alert).toHaveText('');
    await expect(resultRows).toHaveCount(1);
    const cells = resultRows.filter({ hasText: name }).getByRole('cell');
    await expect(cells.nth(1)).toHaveText(name);
    await expect(cells.nth(2)).toHaveText(CHANGED_CITY);
    await expect(cells.nth(3)).toHaveText(created.state);
    await expect(cells.nth(4)).toHaveText(created.zip.slice(0, 5));
    // The option that opened the window is cleared once it closes.
    await expect(optionInput).toHaveValue('');
    // No new search: the row was replaced from the PUT response.
    expect(searchRequests).toHaveLength(1);
  });

  await test.step('option 5 shows the saved record with the change stamp of the Sales user', async () => {
    const stored = await getCustomer(api, created.custId);
    expect(stored.version).toBe(1);
    expect(stored.chgUser).toBe(maintenanceUser);
    expect(stored.city).toBe(CHANGED_CITY);
    // Only the city, the stamp time and the version changed.
    expect(stored).toEqual({ ...created, city: CHANGED_CITY, chgTime: stored.chgTime, version: 1 });

    const changedAt = stored.chgTime;
    if (changedAt === null) {
      throw new Error(`GET /api/customers/${created.custId}: chgTime is null after the update.`);
    }
    // The SPA shows the stamp in browser-local time, so the expected text is built from the
    // browser's own reading of the stored instant.
    const local: LocalDateTime = await page.evaluate((iso: string) => {
      const at = new Date(iso);
      return {
        year: at.getFullYear(),
        month: at.getMonth(),
        day: at.getDate(),
        hours: at.getHours(),
        minutes: at.getMinutes(),
        seconds: at.getSeconds(),
      };
    }, changedAt);

    await optionInput.fill('5');
    await page.keyboard.press('Enter');

    await expect(detail).toBeVisible();
    await expect(detail).toHaveAccessibleName(/Displaying Customer/);
    await expect(nameInput).toHaveValue(name);
    await expect(cityInput).toHaveValue(CHANGED_CITY);
    await expect(cityInput).not.toBeEditable();
    await expect(stampLine).toHaveText(stampPattern(maintenanceUser));
    await expect(stampLine).toHaveText(expectedStamp(local, maintenanceUser));

    await page.keyboard.press('F12');
    await expect(detail).toBeHidden();
    expect(searchRequests).toHaveLength(1);
  });
});
