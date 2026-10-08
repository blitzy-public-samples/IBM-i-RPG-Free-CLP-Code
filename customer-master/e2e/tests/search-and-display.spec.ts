/**
 * F-001 / UC-01 search as Inquiry (PMTCUSTR mode `I`) and UC-02 display (MTNCUSTR function `D`).
 *
 * The list loads on open [5250_Subfile/PMTCUSTR.SQLRPGLE:252-256] offering only 5=Display
 * [5250_Subfile/PMTCUSTR.SQLRPGLE:750-766]. Inactive customers are excluded by default
 * [5250_Subfile/PMTCUSTR.SQLRPGLE:647-653], so `A AUCTOR` gives DEM0002
 * [5250_Subfile/PMTCUSTR.SQLRPGLE:532-545]; F9 includes them
 * [5250_Subfile/PMTCUSTR.SQLRPGLE:406-413] in COLOR(RED) [5250_Subfile/PMTCUSTD.DSPF:66-72].
 * Option 5 displays a customer with every field protected [5250_Subfile/MTNCUSTR.SQLRPGLE:181-191].
 * F6 is not active in Inquiry mode: DEM0003 [5250_Subfile/PMTCUSTR.SQLRPGLE:396-403].
 *
 * The spec only reads the Flyway V5 seed and must run before any generator load. Exactly one seed
 * name starts with NIBH, `NIBH L'LOR COMPANY` (Custmast.sql id 3 = V5 `AAAD`), its apostrophe from
 * the transform [5250_Subfile/Custmast.sql:331]. Exactly two start with `A AUCTOR`, both inactive:
 * ids 71 and 95 are odd and the even-id activation leaves them `N` [5250_Subfile/Custmast.sql:330].
 * Expected texts are verbatim catalog (CUSTMSGF [5250_Subfile/CRTMSGF.CLLE:14-17]) and seed values.
 */
import { expect, test, toasts, USERS } from '../fixtures/auth';
import type { Locator, Page } from '../fixtures/auth';

/** DEM0002, the search notice when no customer matches the criteria. */
const DEM0002 = 'No records match the selection criteria';

/** DEM0003, raised by a function key the screen does not enable now. */
const DEM0003 = 'Key is not active now';

/** Rows on one page of the list: the PMTCUSTD subfile page (SFLPAG 12). */
const PAGE_SIZE = 12;

/** Inputs of the detail window: the protected Customer Id plus the nine customer fields. */
const DETAIL_INPUT_COUNT = 10;

/**
 * COLOR(RED) of inactive rows [5250_Subfile/PMTCUSTD.DSPF:66-72]: the computed value of
 * `--color-inactive` (#b42318, tokens.css).
 */
const INACTIVE_RED = 'rgb(180, 35, 24)';

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

const INACTIVE_ONLY_PREFIX = 'A AUCTOR';

const NIBH_PREFIX = 'NIBH';

const DETAIL_DIALOG_NAME = /Displaying Customer|Change Customer|Add Customer/;

/** The search screen's own header (title, function line, user), not the detail window's. */
function searchHeader(page: Page): Locator {
  return page.locator('main .search-panel > .screen-header');
}

function resultsTable(page: Page): Locator {
  return page.getByRole('table', { name: 'Customers', exact: true });
}

function dataRows(page: Page): Locator {
  return resultsTable(page).locator('tbody').getByRole('row');
}

function cellsOf(row: Locator): { name: Locator; city: Locator; state: Locator; zip5: Locator } {
  const cells = row.getByRole('cell');
  return { name: cells.nth(1), city: cells.nth(2), state: cells.nth(3), zip5: cells.nth(4) };
}

function keyLegend(page: Page): Locator {
  return page.getByRole('toolbar', { name: 'Function keys', exact: true });
}

function nameFilter(page: Page): Locator {
  return page.getByLabel('Name starts with:', { exact: true });
}

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

async function expectRowShows(row: Locator, customer: SeedCustomer): Promise<void> {
  const cells = cellsOf(row);
  // An inactive name carries the visually hidden "Inactive" label after it (ResultsTable).
  await expect(cells.name).toHaveText(customer.active === 'N' ? `${customer.name} Inactive` : customer.name);
  await expect(cells.city).toHaveText(customer.city);
  await expect(cells.state).toHaveText(customer.state);
  await expect(cells.zip5).toHaveText(customer.zip5);
}

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

      await expect(page.getByText('5=Display', { exact: true })).toBeVisible();
      await expect(page.getByText('2=Edit')).toHaveCount(0);
      await expect(rows.first().getByRole('button')).toHaveCount(1);
      await expect(rows.first().getByRole('button')).toHaveText(/^Display\b/);

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

      // An active row is not red; its name colour is compared with the inactive rows in the F9 step.
      const nameColor = await colorOf(cellsOf(row).name);
      expect(nameColor, `colour of ${NIBH.name}, an active row`).not.toBe(INACTIVE_RED);
      return nameColor;
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
        // Every data cell renders exactly COLOR(RED), and the name differs from the active row's.
        const cells = cellsOf(row);
        for (const cell of [cells.name, cells.city, cells.state, cells.zip5]) {
          await expect(cell).toHaveCSS('color', INACTIVE_RED);
        }
        expect(await colorOf(cells.name), `colour of ${customer.name} against an active row`).not.toBe(
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
