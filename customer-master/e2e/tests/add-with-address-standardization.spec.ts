/**
 * End-to-end flow: add a customer through the State prompt and the address standardization
 * (UC-04 add, UC-06 State prompt, UC-07 address standardization).
 * - MTNCUSTR function `A` [5250_Subfile/MTNCUSTR.SQLRPGLE:251-297] opens cleared with ACTIVE = 'Y',
 *   confirms with DEM0009, adds with AddRecd and returns to the list with no message
 *   [5250_Subfile/PMTCUSTR.SQLRPGLE:397-400].
 * - F04Prompt calls PMTSTATER from the State field only [5250_Subfile/MTNCUSTR.SQLRPGLE:364-382],
 *   [5250_Subfile/PMTSTATER.SQLRPGLE].
 * - Edit_Address [USPS_Address/MTNCUSTR.SQLRPGLE:471-499]: success replaces street, city and state
 *   and builds `Zip5-Zip4`; failure sends DEM9898 "USPS: <description>" with Address, City, State
 *   and ZIP highlighted and the cursor on Address.
 *
 * The run needs the default stub client (`ADDRESS_VALIDATION_CLIENT=stub`), which answers fixture
 * F1 of `backend/address-validation/src/main/resources/stub/usps-stub-fixtures.json` with a
 * standardized street and ZIP+4, and any address line containing `BADADDR` with "Address Not
 * Found.". The e2e container mounts only `./e2e`, so the fixture and catalog texts are copied
 * verbatim. Each run names its customers with `uniqueName` and searches with its 11-character
 * `filter`, changing no seed row.
 *
 * Each picker read on F4, Enter and F7 is checked against its `GET /api/states` query and answer.
 * NEW lists the same states in the same order by name and by code, so the prompt is also filtered
 * on VIRGIN before and after F7 (VI, VA, WV by name; VA, VI, WV by code), then NEW is applied
 * again and NH selected.
 */
import { expect, test, toasts, uniqueName, type Locator, type Page } from '../fixtures/auth';

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

type StateRow = { code: string; name: string };

/** The number of STATES rows (V2); the picker opens on all of them, by name. */
const STATE_COUNT = 58;

/**
 * The STATES rows whose name contains "NEW" (V2, from 5250_Subfile/States.sql). Their name order
 * and their code order are the same, so this list is the expected order under both sorts, and
 * on its own it cannot show which order the picker asked for.
 */
const NEW_STATES: ReadonlyArray<StateRow> = Object.freeze([
  { code: 'NH', name: 'New Hampshire' },
  { code: 'NJ', name: 'New Jersey' },
  { code: 'NM', name: 'New Mexico' },
  { code: 'NY', name: 'New York' },
]);

/**
 * The STATES rows whose name contains "VIRGIN", in each picker order. The two orders differ
 * ("Virgin Islands" sorts before "Virginia", while VA precedes VI), which is what makes the F7
 * check discriminating: a list still sorted by name cannot show the code order.
 */
const VIRGIN_STATES: Readonly<{ byName: ReadonlyArray<StateRow>; byCode: ReadonlyArray<StateRow> }> = Object.freeze({
  byName: Object.freeze([
    { code: 'VI', name: 'Virgin Islands' },
    { code: 'VA', name: 'Virginia' },
    { code: 'WV', name: 'West Virginia' },
  ]),
  byCode: Object.freeze([
    { code: 'VA', name: 'Virginia' },
    { code: 'VI', name: 'Virgin Islands' },
    { code: 'WV', name: 'West Virginia' },
  ]),
});

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

function cell(row: Locator, column: number): Locator {
  return row.getByRole('cell').nth(column);
}

const STATE_COLUMN = Object.freeze({ code: 1, name: 2 });

const RESULT_COLUMN = Object.freeze({ name: 1, city: 2, state: 3, zip: 4 });

/**
 * Checks that the picker lists exactly `states`, in that order, once its reload has settled (the
 * table reports `aria-busy="false"`).
 */
async function expectStates(page: Page, picker: Locator, states: ReadonlyArray<StateRow>): Promise<void> {
  await expect(picker.getByRole('table')).toHaveAttribute('aria-busy', 'false');
  const rows = dataRows(page, picker);
  await expect(rows).toHaveCount(states.length);
  for (const [index, state] of states.entries()) {
    await expect(cell(rows.nth(index), STATE_COLUMN.code)).toHaveText(state.code);
    await expect(cell(rows.nth(index), STATE_COLUMN.name)).toHaveText(state.name);
  }
}

type StateSort = 'name' | 'code';

/** One `GET /api/states` the picker sent: its query parameters and the codes it answered, in order. */
type StatesRead = { nameContains: string | null; sort: string | null; codes: string[] };

function isStateResponse(row: unknown): row is { state: string; name: string } {
  return (
    typeof row === 'object' &&
    row !== null &&
    'state' in row &&
    typeof row.state === 'string' &&
    'name' in row &&
    typeof row.name === 'string'
  );
}

/**
 * Runs `press`, the key press that makes the picker read the states, and returns the
 * `GET /api/states` it sent once that read has answered 200. The wait starts before the press,
 * so the answer cannot arrive unobserved; each caller awaits the previous read first, so the
 * answer is the one this press caused.
 */
async function readStates(page: Page, press: () => Promise<void>): Promise<StatesRead> {
  const answered = page.waitForResponse(
    (response) => response.request().method() === 'GET' && new URL(response.url()).pathname === '/api/states',
  );
  const [response] = await Promise.all([answered, press()]);
  expect(response.status(), `status of GET ${response.url()}`).toBe(200);
  const body: unknown = await response.json();
  if (!Array.isArray(body) || !body.every(isStateResponse)) {
    throw new Error(`GET ${response.url()} did not answer a list of { state, name }: ${JSON.stringify(body)}`);
  }
  const query = new URL(response.url()).searchParams;
  return { nameContains: query.get('nameContains'), sort: query.get('sort'), codes: body.map((row) => row.state) };
}

/**
 * Runs `press` and checks the read it made the picker send: `nameContains` and `sort` as given,
 * answered with the codes of `states` in that order.
 */
async function expectStatesRead(
  page: Page,
  press: () => Promise<void>,
  nameContains: string,
  sort: StateSort,
  states: ReadonlyArray<StateRow>,
): Promise<void> {
  const read = await readStates(page, press);
  expect(read, `GET /api/states for "${nameContains}" by ${sort}`).toEqual({
    nameContains,
    sort,
    codes: states.map((state) => state.code),
  });
}

async function fillAccountFields(dialog: Locator): Promise<void> {
  await field(dialog, 'Account Manager Phone').fill(ACCOUNT.acctPhone);
  await field(dialog, 'Account Manager Name').fill(ACCOUNT.acctMgr);
  await field(dialog, 'Corporate Phone').fill(ACCOUNT.corpPhone);
}

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

  await test.step('F4 on State + prompts: filter VIRGIN and NEW, F7 to sort by code, VIRGIN reorders, option 1 on NH', async () => {
    const add = detailDialog(page, /Add Customer/);
    const state = field(add, 'State +');
    // press() focuses the field first: F4 prompts only while focus is on "State +". The opening
    // read is awaited before any filter is typed, so every later read is the one its key sent.
    const opened = await readStates(page, () => state.press('F4'));
    expect(opened.nameContains, 'the opening read has no filter').toBe('');
    expect(opened.sort, 'the opening read is by name').toBe('name');
    expect(opened.codes, 'the opening read answers every state').toHaveLength(STATE_COUNT);

    const picker = statePicker(page);
    await expect(picker).toBeVisible();
    await expect(picker.getByText('Sorted by: Name', { exact: true })).toBeVisible();
    await expect(picker.getByRole('columnheader', { name: 'Name', exact: true })).toHaveAttribute('aria-sort', 'ascending');
    await expect(picker.getByRole('button', { name: 'F7=By Code', exact: true })).toBeVisible();

    const filter = picker.getByLabel('Name Contains', { exact: true });
    await filter.fill('VIRGIN');
    await expectStatesRead(page, () => filter.press('Enter'), 'VIRGIN', 'name', VIRGIN_STATES.byName);
    await expectStates(page, picker, VIRGIN_STATES.byName);

    await filter.fill('NEW');
    await expectStatesRead(page, () => filter.press('Enter'), 'NEW', 'name', NEW_STATES);
    await expectStates(page, picker, NEW_STATES);

    await expectStatesRead(page, () => page.keyboard.press('F7'), 'NEW', 'code', NEW_STATES);
    await expect(picker.getByText('Sorted by: Code', { exact: true })).toBeVisible();
    await expect(picker.getByRole('button', { name: 'F7=By Name', exact: true })).toBeVisible();
    await expect(picker.getByRole('columnheader', { name: 'Code', exact: true })).toHaveAttribute('aria-sort', 'ascending');
    await expect(picker.getByRole('columnheader', { name: 'Name', exact: true })).not.toHaveAttribute('aria-sort');
    await expect(filter).toHaveValue('NEW');
    await expectStates(page, picker, NEW_STATES);

    // NEW reads the same in both orders, so VIRGIN shows the sort took effect: Enter keeps the
    // code order F7 chose, and VA before VI is an order a name-sorted list cannot produce.
    await filter.fill('VIRGIN');
    await expectStatesRead(page, () => filter.press('Enter'), 'VIRGIN', 'code', VIRGIN_STATES.byCode);
    await expectStates(page, picker, VIRGIN_STATES.byCode);

    await filter.fill('NEW');
    await expectStatesRead(page, () => filter.press('Enter'), 'NEW', 'code', NEW_STATES);
    await expect(picker.getByText('Sorted by: Code', { exact: true })).toBeVisible();
    await expectStates(page, picker, NEW_STATES);
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
