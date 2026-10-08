/**
 * End-to-end flow: add a customer through the State prompt and the address standardization.
 *
 * What it exercises (UC-04 add, UC-06 State prompt, UC-07 address standardization), as the
 * source members behave:
 * - MTNCUSTR function `A` [5250_Subfile/MTNCUSTR.SQLRPGLE:251-297]: the window opens cleared
 *   with ACTIVE = 'Y'; Enter edits the fields and, when they pass, protects them and shows the
 *   confirmation with DEM0009; Enter there adds the row (AddRecd takes the next id) and closes
 *   the window, and PMTCUSTR returns to the list with no message
 *   [5250_Subfile/PMTCUSTR.SQLRPGLE:397-400].
 * - F04Prompt [5250_Subfile/MTNCUSTR.SQLRPGLE:364-382] calls PMTSTATER from the State field
 *   only. PMTSTATER [5250_Subfile/PMTSTATER.SQLRPGLE] loads all states by name, filters on
 *   "Name Contains", toggles the order with F7 ("Sorted by:" Name / Code, legend F7=By Code /
 *   F7=By Name) and returns the code of the row given option 1; F4 runs no edit, so every other
 *   typed field survives the prompt.
 * - Edit_Address [USPS_Address/MTNCUSTR.SQLRPGLE:471-499]: on success the street, city and
 *   state are replaced by the service's values and the ZIP becomes `Zip5-Zip4`; on failure
 *   DEM9898 "USPS: <description>" is sent, the cursor goes to the address and Address, City,
 *   State and ZIP are shown in reverse image.
 *
 * How it runs. Against the Compose stack with the default stub address client
 * (`ADDRESS_VALIDATION_CLIENT=stub`), signed in as the Sales user (role MAINTENANCE). The stub
 * answers fixture F1 of `backend/address-validation/src/main/resources/stub/usps-stub-fixtures.json`
 * with a standardized street and a ZIP+4, and answers any address line containing `BADADDR`
 * with "Address Not Found.". The e2e container mounts only `./e2e`, so the fixture and the
 * catalog texts are copied here verbatim rather than read at run time.
 *
 * Independence. Each run names its customers with `uniqueName`, and every search uses the
 * returned 11-character `filter`, which matches only that run's customer; rows added by earlier
 * runs therefore never change what a search here finds. The spec changes no seed row.
 *
 * Keyboard contract the steps rely on: only the topmost window receives keys (State picker over
 * the detail window over the search page); Enter is a command only with focus in a text input,
 * an option field or the window's own container, so every Enter is pressed on such an element;
 * toasts clear on each click and each command key, so each message is asserted right after the
 * action that raised it.
 */
import { expect, test, toasts, uniqueName, type Locator, type Page } from '../fixtures/auth';

// ---------------------------------------------------------------------------
// Test data (copied verbatim from the stub fixtures, the catalog and the STATES rows)
// ---------------------------------------------------------------------------

/**
 * Stub fixture F1: the address keyed (street, city, state, ZIP) and what the stub returns for it
 * (`address2` and `zip4`; city and state come back unchanged and non-blank, so the standardization
 * counts as a success). NH is a STATES code and the first "NEW" state in either picker order.
 */
const F1 = Object.freeze({
  addr: '41 QUARRY HILL ROAD',
  city: 'GRANITE FALLS',
  state: 'NH',
  zip: '03999',
  stdAddr: '41 QUARRY HILL RD',
  zip4: '2210',
});

/** The ZIP Edit_Address builds when a ZIP+4 is returned: `Zip5-Zip4`. */
const STANDARDIZED_ZIP = `${F1.zip}-${F1.zip4}`;

/** A street the stub rejects: any address line containing `BADADDR` is "Address Not Found.". */
const NOT_FOUND_ADDR = '1 BADADDR LANE';

/** Valid values for the three account fields, which only have to be non-blank. */
const ACCOUNT = Object.freeze({
  acctPhone: '(603) 555-0101',
  acctMgr: 'JANE ROE',
  corpPhone: '(603) 555-0100',
});

/** DEM0009, the add confirmation (catalog text with the source's double space corrected). */
const DEM0009 = 'Press Enter to add. Press F12 to cancel';

/** DEM9898 "USPS: {0}" with the stub's not-found description as the argument. */
const DEM9898_NOT_FOUND = 'USPS: Address Not Found.';

/** DEM0002, the notice of a search that matches nothing. */
const DEM0002 = 'No records match the selection criteria';

/**
 * The STATES rows whose name contains "NEW" (V2, from 5250_Subfile/States.sql). Their name order
 * and their code order are the same, so this list is the expected order under both sorts.
 */
const NEW_STATES: ReadonlyArray<{ code: string; name: string }> = Object.freeze([
  { code: 'NH', name: 'New Hampshire' },
  { code: 'NJ', name: 'New Jersey' },
  { code: 'NM', name: 'New Mexico' },
  { code: 'NY', name: 'New York' },
]);

// ---------------------------------------------------------------------------
// Locators
// ---------------------------------------------------------------------------

/** The labels of the detail window's fields (CustomerForm and ConfirmationPanel share them). */
type DetailFieldLabel =
  | 'Customer Id'
  | 'Active (Y/N)'
  | 'Name'
  | 'Address'
  | 'City'
  | 'State +'
  | 'ZIP'
  | 'Account Manager Phone'
  | 'Account Manager Name'
  | 'Corporate Phone';

/** The eight data fields an opened add form must show empty (Active is preset to `Y`). */
const EMPTY_ON_ADD: ReadonlyArray<DetailFieldLabel> = Object.freeze([
  'Name',
  'Address',
  'City',
  'State +',
  'ZIP',
  'Account Manager Phone',
  'Account Manager Name',
  'Corporate Phone',
]);

/** The fields DEM9898 highlights, in the order the server reports them; the first gets focus. */
const ADDRESS_FIELDS: ReadonlyArray<DetailFieldLabel> = Object.freeze(['Address', 'City', 'State +', 'ZIP']);

/** The fields a DEM9898 leaves unmarked. */
const NON_ADDRESS_FIELDS: ReadonlyArray<DetailFieldLabel> = Object.freeze([
  'Active (Y/N)',
  'Name',
  'Account Manager Phone',
  'Account Manager Name',
  'Corporate Phone',
]);

/**
 * The customer detail window in any function. It is named by its header ("Customer Master"
 * plus the function line), so the function text tells display, change and add apart.
 */
function detailDialog(page: Page, functionText: RegExp = /Displaying Customer|Change Customer|Add Customer/): Locator {
  return page.getByRole('dialog', { name: functionText });
}

/** The USA States prompt window. */
function statePicker(page: Page): Locator {
  return page.getByRole('dialog', { name: /USA States/ });
}

/**
 * One labelled field inside `scope`. The match is exact, so "Name" never resolves to
 * "Account Manager Name" (nor, while the picker is open, to its "Name Contains" filter).
 */
function field(scope: Locator, label: DetailFieldLabel): Locator {
  return scope.getByLabel(label, { exact: true });
}

/** The data rows of a table inside `scope`: rows holding cells, never the heading row. */
function dataRows(page: Page, scope: Locator): Locator {
  return scope.getByRole('row').filter({ has: page.getByRole('cell') });
}

/** The cell at 0-based column `column` of `row`. */
function cell(row: Locator, column: number): Locator {
  return row.getByRole('cell').nth(column);
}

/** Columns of the State picker table: Opt, Code, Name, actions. */
const STATE_COLUMN = Object.freeze({ code: 1, name: 2 });

/** Columns of the customer results table: Opt, Customer Name, City, St, ZIP, actions. */
const RESULT_COLUMN = Object.freeze({ name: 1, city: 2, state: 3, zip: 4 });

/**
 * Checks that the picker lists exactly the four "NEW" states, in order, once its reload has
 * settled (the table reports `aria-busy="false"`).
 */
async function expectNewStates(page: Page, picker: Locator): Promise<void> {
  await expect(picker.getByRole('table')).toHaveAttribute('aria-busy', 'false');
  const rows = dataRows(page, picker);
  await expect(rows).toHaveCount(NEW_STATES.length);
  for (const [index, state] of NEW_STATES.entries()) {
    await expect(cell(rows.nth(index), STATE_COLUMN.code)).toHaveText(state.code);
    await expect(cell(rows.nth(index), STATE_COLUMN.name)).toHaveText(state.name);
  }
}

/** Types the three account fields of an open add or change form. */
async function fillAccountFields(dialog: Locator): Promise<void> {
  await field(dialog, 'Account Manager Phone').fill(ACCOUNT.acctPhone);
  await field(dialog, 'Account Manager Name').fill(ACCOUNT.acctMgr);
  await field(dialog, 'Corporate Phone').fill(ACCOUNT.corpPhone);
}

// ---------------------------------------------------------------------------
// The flow
// ---------------------------------------------------------------------------

test.use({ startPath: '/customers' });

test('adds a customer through the State prompt with a standardized address, and rejects an address USPS cannot find', async ({
  asMaintenance: page,
}) => {
  const first = uniqueName('ADD');
  const second = uniqueName('BAD');
  const notices = toasts(page);
  const results = page.getByRole('table', { name: 'Customers' });
  const nameFilter = page.getByLabel('Name starts with:', { exact: true });

  await test.step('F6 opens the add window cleared, with Active preset to Y', async () => {
    // F6=Add is offered in Maintenance mode only, so its legend shows the session has loaded.
    await expect(page.getByRole('button', { name: 'F6=Add', exact: true })).toBeVisible();
    await page.keyboard.press('F6');

    const add = detailDialog(page, /Add Customer/);
    await expect(add).toBeVisible();
    await expect(field(add, 'Customer Id')).toHaveValue('');
    await expect(field(add, 'Active (Y/N)')).toHaveValue('Y');
    for (const label of EMPTY_ON_ADD) {
      await expect(field(add, label), `${label} is empty on add`).toHaveValue('');
    }
  });

  await test.step('type the name and the street and city of stub fixture F1', async () => {
    const add = detailDialog(page, /Add Customer/);
    await field(add, 'Name').fill(first.name);
    await field(add, 'Address').fill(F1.addr);
    await field(add, 'City').fill(F1.city);
  });

  await test.step('F4 on State + prompts: filter NEW, F7 to sort by code, option 1 on NH', async () => {
    const add = detailDialog(page, /Add Customer/);
    const state = field(add, 'State +');
    // press() focuses the field first: F4 prompts only while focus is on "State +".
    await state.press('F4');

    const picker = statePicker(page);
    await expect(picker).toBeVisible();
    await expect(picker.getByText('Sorted by: Name', { exact: true })).toBeVisible();
    await expect(picker.getByRole('columnheader', { name: 'Name', exact: true })).toHaveAttribute('aria-sort', 'ascending');
    await expect(picker.getByRole('button', { name: 'F7=By Code', exact: true })).toBeVisible();

    const filter = picker.getByLabel('Name Contains', { exact: true });
    await filter.fill('NEW');
    await filter.press('Enter');
    await expectNewStates(page, picker);

    await page.keyboard.press('F7');
    await expect(picker.getByText('Sorted by: Code', { exact: true })).toBeVisible();
    await expect(picker.getByRole('button', { name: 'F7=By Name', exact: true })).toBeVisible();
    await expect(picker.getByRole('columnheader', { name: 'Code', exact: true })).toHaveAttribute('aria-sort', 'ascending');
    await expect(picker.getByRole('columnheader', { name: 'Name', exact: true })).not.toHaveAttribute('aria-sort');
    await expect(filter).toHaveValue('NEW');
    await expectNewStates(page, picker);
    await expect(cell(dataRows(page, picker).first(), STATE_COLUMN.code)).toHaveText(F1.state);

    const nhRow = dataRows(page, picker).filter({ has: page.getByRole('cell', { name: F1.state, exact: true }) });
    const nhOption = nhRow.getByRole('textbox');
    await nhOption.fill('1');
    await nhOption.press('Enter');

    await expect(picker).toBeHidden();
    await expect(add).toBeVisible();
    await expect(state).toHaveValue(F1.state);
    await expect(state).toBeFocused();
    // F4 runs no edit: the fields typed before the prompt are kept as typed, not standardized.
    await expect(field(add, 'Name')).toHaveValue(first.name);
    await expect(field(add, 'Address')).toHaveValue(F1.addr);
    await expect(field(add, 'City')).toHaveValue(F1.city);
  });

  await test.step('Enter reviews: the confirmation shows the standardized street and ZIP+4 with DEM0009', async () => {
    const add = detailDialog(page, /Add Customer/);
    await field(add, 'ZIP').fill(F1.zip);
    await fillAccountFields(add);
    await field(add, 'Corporate Phone').press('Enter');

    await expect(notices.status).toContainText(DEM0009);
    await expect(notices.alert).toHaveText('');

    const confirmation = add.getByRole('group', { name: 'Confirm customer' });
    await expect(confirmation).toBeVisible();
    await expect(confirmation.getByText('Address standardized.', { exact: true })).toBeVisible();
    await expect(field(confirmation, 'Address')).toHaveValue(F1.stdAddr);
    await expect(field(confirmation, 'ZIP')).toHaveValue(STANDARDIZED_ZIP);
    await expect(field(confirmation, 'City')).toHaveValue(F1.city);
    await expect(field(confirmation, 'State +')).toHaveValue(F1.state);
    await expect(field(confirmation, 'Name')).toHaveValue(first.name);
    await expect(field(confirmation, 'Active (Y/N)')).toHaveValue('Y');
    await expect(field(confirmation, 'Customer Id')).toHaveValue('');
    // ProtectAll: every confirmed value is read-only.
    await expect(field(confirmation, 'Address')).not.toBeEditable();
    await expect(field(confirmation, 'ZIP')).not.toBeEditable();
    await expect(field(confirmation, 'Name')).not.toBeEditable();
  });

  await test.step('Enter at the confirmation adds the customer and closes the window with no message', async () => {
    // Pressed on the confirmation panel, the window's key container, where Enter is the commit.
    await detailDialog(page, /Add Customer/).getByRole('group', { name: 'Confirm customer' }).press('Enter');

    await expect(detailDialog(page)).toBeHidden();
    await expect(notices.alert).toHaveText('');
    await expect(notices.status).toHaveText('');
  });

  await test.step('the new customer is found by name, with a 4-character id and the standardized address', async () => {
    await nameFilter.fill(first.filter);
    await nameFilter.press('Enter');

    const rows = dataRows(page, results);
    await expect(rows).toHaveCount(1);
    const row = rows.first();
    await expect(cell(row, RESULT_COLUMN.name)).toHaveText(first.name);
    await expect(cell(row, RESULT_COLUMN.city)).toHaveText(F1.city);
    await expect(cell(row, RESULT_COLUMN.state)).toHaveText(F1.state);
    await expect(cell(row, RESULT_COLUMN.zip)).toHaveText(F1.zip);

    const option = row.getByRole('textbox', { name: `Option for ${first.name}`, exact: true });
    await option.fill('5');
    await option.press('Enter');

    const display = detailDialog(page, /Displaying Customer/);
    await expect(display).toBeVisible();
    await expect(field(display, 'Customer Id')).toHaveValue(/^[A-Z0-9]{4}$/);
    await expect(field(display, 'Name')).toHaveValue(first.name);
    await expect(field(display, 'Address')).toHaveValue(F1.stdAddr);
    await expect(field(display, 'City')).toHaveValue(F1.city);
    await expect(field(display, 'State +')).toHaveValue(F1.state);
    await expect(field(display, 'ZIP')).toHaveValue(STANDARDIZED_ZIP);
    await expect(field(display, 'Active (Y/N)')).toHaveValue('Y');
    await expect(field(display, 'Address')).not.toBeEditable();

    await page.keyboard.press('F12');
    await expect(display).toBeHidden();
  });

  await test.step('an address USPS cannot find: DEM9898 on Address, City, State and ZIP, and nothing is added', async () => {
    await page.keyboard.press('F6');

    const add = detailDialog(page, /Add Customer/);
    await expect(add).toBeVisible();
    await expect(field(add, 'Active (Y/N)')).toHaveValue('Y');
    await field(add, 'Name').fill(second.name);
    await field(add, 'Address').fill(NOT_FOUND_ADDR);
    await field(add, 'City').fill(F1.city);
    // Typed, not prompted: the State rule only needs a code that exists in STATES.
    await field(add, 'State +').fill(F1.state);
    await field(add, 'ZIP').fill(F1.zip);
    await fillAccountFields(add);
    await field(add, 'Corporate Phone').press('Enter');

    await expect(notices.alert).toContainText(DEM9898_NOT_FOUND);
    for (const label of ADDRESS_FIELDS) {
      await expect(field(add, label), `${label} is highlighted by DEM9898`).toHaveAttribute('aria-invalid', 'true');
    }
    for (const label of NON_ADDRESS_FIELDS) {
      await expect(field(add, label), `${label} is not highlighted by DEM9898`).not.toHaveAttribute('aria-invalid');
    }
    await expect(field(add, 'Address')).toBeFocused();
    // The review failed, so the window stays on the editable form with the entries kept.
    await expect(add.getByRole('group', { name: 'Confirm customer' })).toHaveCount(0);
    await expect(field(add, 'Address')).toHaveValue(NOT_FOUND_ADDR);

    await page.keyboard.press('F12');
    await expect(add).toBeHidden();

    await nameFilter.fill(second.filter);
    await nameFilter.press('Enter');
    await expect(notices.status).toContainText(DEM0002);
    await expect(dataRows(page, results)).toHaveCount(0);
  });
});
