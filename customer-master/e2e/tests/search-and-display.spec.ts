/**
 * search-and-display: the Inquiry search-and-display flow (F-001 / UC-01 search, UC-02 display).
 *
 * Signed in as the general user (role INQUIRY), the spec walks the customer search screen and the
 * read-only detail window as PMTCUSTR in mode `I` and MTNCUSTR with function code `D` behave:
 *
 *   1. The first page loads on open, with no key pressed [5250_Subfile/PMTCUSTR.SQLRPGLE:252-256],
 *      and the screen offers only `5=Display` [5250_Subfile/PMTCUSTR.SQLRPGLE:750-766].
 *   2. "Name starts with:" `NIBH` finds the seed customer `NIBH L'LOR COMPANY` (Custmast.sql id 3,
 *      V5 id `AAAD`), its apostrophe shown literally [5250_Subfile/Custmast.sql:331].
 *   3. Inactive customers are excluded by default (`ACTIVE BETWEEN 'Y' AND 'Y'`,
 *      [5250_Subfile/PMTCUSTR.SQLRPGLE:647-653]): `A AUCTOR` matches only inactive rows, so the
 *      list is empty and DEM0002 is shown [5250_Subfile/PMTCUSTR.SQLRPGLE:532-545].
 *   4. F9 includes them [5250_Subfile/PMTCUSTR.SQLRPGLE:406-413]: both rows appear in red
 *      (`COLOR(RED)` on indicator 83, [5250_Subfile/PMTCUSTD.DSPF:66-72]) with a visually hidden
 *      "Inactive" label, and the legend switches to "F9=Exclude Inactive".
 *   5. Option 5 plus Enter opens the detail window in Display mode with every field protected
 *      (ProtectAll, [5250_Subfile/MTNCUSTR.SQLRPGLE:181-191]).
 *   6. F12 closes it, the one screen I/O of Display mode, and focus returns to the option field.
 *   7. F6 is not active in Inquiry mode: DEM0003 [5250_Subfile/PMTCUSTR.SQLRPGLE:396-403].
 *
 * Data. The spec runs against the Compose stack with the Flyway V5 seed loaded (before any
 * generator run) and only reads it: no customer is created or changed, so it can be repeated.
 * Exactly one seed name starts with `NIBH` (active) and exactly two with `A AUCTOR`, both inactive
 * (Custmast.sql ids 71 and 95 are odd, so the even-id activation transform leaves them `N`,
 * [5250_Subfile/Custmast.sql:330]).
 *
 * Constraints inherited from the shared fixtures (`../fixtures/auth`):
 * - Credentials live in the page's memory only, so after sign-in the spec never navigates: the
 *   start screen comes from `startPath`, and every move is a key press or a field entry.
 * - Enter is a command only while focus is in a text input, an option field or the screen, so
 *   each Enter is pressed on the field just filled. Function keys are commands wherever focus is.
 * - Messages clear on the next click or command key, so each one is asserted right after the
 *   action that raised it.
 *
 * Expected texts are the message catalog (messages.properties, from CUSTMSGF
 * [5250_Subfile/CRTMSGF.CLLE:14-17]) and the V5 seed values, verbatim.
 */
import { expect, test, toasts, USERS } from '../fixtures/auth';
import type { Locator, Page } from '../fixtures/auth';

// ---------------------------------------------------------------------------
// Expected values
// ---------------------------------------------------------------------------

/** DEM0002, the search notice when no customer matches the criteria. */
const DEM0002 = 'No records match the selection criteria';

/** DEM0003, raised by a function key the screen does not enable now. */
const DEM0003 = 'Key is not active now';

/** Rows on one page of the list: the PMTCUSTD subfile page (SFLPAG 12). */
const PAGE_SIZE = 12;

/** Inputs of the detail window: the protected Customer Id plus the nine customer fields. */
const DETAIL_INPUT_COUNT = 10;

/** One customer as the list and the detail window show it. */
type SeedCustomer = {
  readonly custId: string;
  readonly name: string;
  readonly city: string;
  readonly state: string;
  readonly zip5: string;
  readonly active: 'Y' | 'N';
};

/** V5 `AAAD` (Custmast.sql id 3, DOLOR replaced by L'LOR): the one seed name starting with NIBH. */
const NIBH: SeedCustomer = {
  custId: 'AAAD',
  name: "NIBH L'LOR COMPANY",
  city: 'AUBURN',
  state: 'ME',
  zip5: '15762',
  active: 'Y',
};

/**
 * The two seed customers whose names start with `A AUCTOR`, both inactive, in list order
 * (`ORDER BY name, city, state, custid`): V5 `AACX` (id 95) and `AAB9` (id 71).
 */
const A_AUCTOR_INACTIVE: readonly SeedCustomer[] = [
  { custId: 'AACX', name: 'A AUCTOR CONSULTING', city: 'SANDY', state: 'UT', zip5: '29191', active: 'N' },
  { custId: 'AAB9', name: 'A AUCTOR LLP', city: 'BOWLING GREEN', state: 'KY', zip5: '18191', active: 'N' },
];

/** The name prefix that matches only the two inactive `A AUCTOR` customers. */
const INACTIVE_ONLY_PREFIX = 'A AUCTOR';

/** The name prefix that matches only `NIBH L'LOR COMPANY`. */
const NIBH_PREFIX = 'NIBH';

/** The detail window, named by its header: "Customer Master" plus the function line. */
const DETAIL_DIALOG_NAME = /Displaying Customer|Change Customer|Add Customer/;

// ---------------------------------------------------------------------------
// Locators and helpers
// ---------------------------------------------------------------------------

/** The search screen's own header (title, function line, user), not the detail window's. */
function searchHeader(page: Page): Locator {
  return page.locator('main .search-panel > .screen-header');
}

/** The results table, named by its caption. */
function resultsTable(page: Page): Locator {
  return page.getByRole('table', { name: 'Customers', exact: true });
}

/** The data rows of the results table; the heading row is never counted. */
function dataRows(page: Page): Locator {
  return resultsTable(page).locator('tbody').getByRole('row');
}

/** The cells of one data row: Opt, Customer Name, City, St, ZIP, actions. */
function cellsOf(row: Locator): { name: Locator; city: Locator; state: Locator; zip5: Locator } {
  const cells = row.getByRole('cell');
  return { name: cells.nth(1), city: cells.nth(2), state: cells.nth(3), zip5: cells.nth(4) };
}

/** The visible function-key legend of the screen. */
function keyLegend(page: Page): Locator {
  return page.getByRole('toolbar', { name: 'Function keys', exact: true });
}

/** The "Name starts with:" filter of the search screen. */
function nameFilter(page: Page): Locator {
  return page.getByLabel('Name starts with:', { exact: true });
}

/** The rendered text colour of an element, as the browser computes it. */
async function colorOf(locator: Locator): Promise<string> {
  return locator.evaluate((element) => getComputedStyle(element).color);
}

/**
 * Types `prefix` into "Name starts with:" and presses Enter on that field, which runs a new search
 * because the criteria differ from the ones last applied. The value is checked first, so Enter is
 * pressed only once the screen holds exactly the typed (already uppercase) criteria.
 */
async function searchByName(page: Page, prefix: string): Promise<void> {
  const filter = nameFilter(page);
  await filter.fill(prefix);
  await expect(filter).toHaveValue(prefix);
  await filter.press('Enter');
}

/** Checks that `row` shows `customer` in its Customer Name, City, St and ZIP cells. */
async function expectRowShows(row: Locator, customer: SeedCustomer): Promise<void> {
  const cells = cellsOf(row);
  // An inactive name carries the visually hidden "Inactive" label after it (ResultsTable).
  await expect(cells.name).toHaveText(customer.active === 'N' ? `${customer.name} Inactive` : customer.name);
  await expect(cells.city).toHaveText(customer.city);
  await expect(cells.state).toHaveText(customer.state);
  await expect(cells.zip5).toHaveText(customer.zip5);
}

// ---------------------------------------------------------------------------
// The flow
// ---------------------------------------------------------------------------

test.describe('search and display (Inquiry)', () => {
  test.use({ startPath: '/customers' });

  test('searches by name, includes inactive rows with F9, displays a customer read-only, rejects F6', async ({
    asInquiry: page,
  }) => {
    await test.step('Inquiry loads the first page on open and offers 5=Display only', async () => {
      const header = searchHeader(page);
      await expect(header.locator('.screen-header__function')).toHaveText('Inquiry');
      await expect(header.locator('.screen-header__user')).toContainText(USERS.inquiry.username);

      // No key pressed: the list is already there (SflFirstPage on entry in mode I).
      const rows = dataRows(page);
      await expect(rows.first()).toBeVisible();
      await expect.poll(async () => rows.count(), { message: 'rows on the first page' }).toBeGreaterThanOrEqual(1);
      await expect.poll(async () => rows.count(), { message: 'rows on the first page' }).toBeLessThanOrEqual(PAGE_SIZE);

      // Options and row actions of Inquiry mode: 5=Display, never 2=Edit.
      await expect(page.getByText('5=Display', { exact: true })).toBeVisible();
      await expect(page.getByText('2=Edit')).toHaveCount(0);
      await expect(rows.first().getByRole('button')).toHaveCount(1);
      await expect(rows.first().getByRole('button')).toHaveText(/^Display\b/);

      // F6=Add is shown in Maintenance only; inactive rows are excluded on entry.
      const legend = keyLegend(page);
      await expect(legend.getByRole('button', { name: 'F6=Add', exact: true })).toHaveCount(0);
      await expect(legend.getByRole('button', { name: 'F9=Include Inactive', exact: true })).toBeVisible();
      await expect(page.getByText('Including Inactives', { exact: true })).toHaveCount(0);
    });

    const activeColor = await test.step(`"Name starts with:" ${NIBH_PREFIX} finds ${NIBH.name}`, async () => {
      await searchByName(page, NIBH_PREFIX);

      const rows = dataRows(page);
      await expect(rows).toHaveCount(1);
      const row = rows.first();
      await expectRowShows(row, NIBH);
      await expect(row).not.toHaveClass(/row--inactive/);

      // The colour of an active row's name, compared with the inactive rows in the F9 step.
      return colorOf(cellsOf(row).name);
    });

    await test.step(`inactive rows are excluded by default: ${INACTIVE_ONLY_PREFIX} gives DEM0002`, async () => {
      await searchByName(page, INACTIVE_ONLY_PREFIX);

      // A notice is a status toast; either live region is accepted, but exactly one holds it.
      const { status, alert } = toasts(page);
      await expect(status.or(alert).filter({ hasText: DEM0002 })).toBeVisible();
      await expect(dataRows(page)).toHaveCount(0);
    });

    await test.step('F9 includes inactive rows, shown in red with a hidden "Inactive" label', async () => {
      await page.keyboard.press('F9');

      const rows = dataRows(page);
      await expect(rows).toHaveCount(A_AUCTOR_INACTIVE.length);
      for (const [index, customer] of A_AUCTOR_INACTIVE.entries()) {
        const row = rows.nth(index);
        await expectRowShows(row, customer);
        await expect(row).toHaveClass(/row--inactive/);
        await expect(row).toContainText('Inactive');
        expect(await colorOf(cellsOf(row).name), `colour of ${customer.name} against an active row`).not.toBe(
          activeColor,
        );
      }

      await expect(page.getByText('Including Inactives', { exact: true })).toBeVisible();
      await expect(keyLegend(page).getByRole('button', { name: 'F9=Exclude Inactive', exact: true })).toBeVisible();
    });

    const optionField = page.getByLabel(`Option for ${NIBH.name}`, { exact: true });
    const detail = page.getByRole('dialog', { name: DETAIL_DIALOG_NAME });

    await test.step('option 5 opens the customer in Display mode with every field protected', async () => {
      await searchByName(page, NIBH_PREFIX);
      await expect(dataRows(page)).toHaveCount(1);

      await optionField.fill('5');
      await expect(optionField).toHaveValue('5');
      await optionField.press('Enter');

      await expect(detail).toBeVisible();
      await expect(detail).toHaveAccessibleName(/Displaying Customer/);
      await expect(detail.getByLabel('Customer Id', { exact: true })).toHaveValue(NIBH.custId);
      await expect(detail.getByLabel('Active (Y/N)', { exact: true })).toHaveValue(NIBH.active);
      await expect(detail.getByLabel('Name', { exact: true })).toHaveValue(NIBH.name);
      await expect(detail.getByLabel('City', { exact: true })).toHaveValue(NIBH.city);
      await expect(detail.getByLabel('State +', { exact: true })).toHaveValue(NIBH.state);

      const inputs = detail.locator('input');
      await expect(inputs).toHaveCount(DETAIL_INPUT_COUNT);
      for (const input of await inputs.all()) {
        await expect(input).not.toBeEditable();
      }
    });

    await test.step('F12 closes the display window and returns to the list', async () => {
      await page.keyboard.press('F12');

      await expect(detail).toHaveCount(0);
      await expect(page.getByRole('dialog')).toHaveCount(0);
      // Focus returns to the invoking option field, which is cleared once its window closes.
      await expect(optionField).toBeFocused();
      await expect(optionField).toHaveValue('');
      await expect(dataRows(page)).toHaveCount(1);
    });

    await test.step('F6 is not active in Inquiry mode: DEM0003', async () => {
      await page.keyboard.press('F6');

      await expect(toasts(page).alert).toContainText(DEM0003);
      await expect(page.getByRole('dialog')).toHaveCount(0);
      await expect(dataRows(page)).toHaveCount(1);
    });
  });
});
