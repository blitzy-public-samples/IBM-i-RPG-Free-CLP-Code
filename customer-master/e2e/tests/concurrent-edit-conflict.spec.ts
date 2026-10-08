/**
 * Concurrent edit conflict: two Sales sessions change the same customer.
 *
 * Source. MTNCUSTR's UpdateRecd makes the UPDATE conditional on the CHGTIME
 * read when the record was displayed. When the row has changed meanwhile, it
 * sends DEM1002 and re-reads the record; no lock is held while the user thinks
 * [5250_Subfile/MTNCUSTR.SQLRPGLE:567-607], [5250_Subfile/CRTMSGF.CLLE:40-41].
 *
 * Target. The PUT is conditional on the `version` read when the window opened;
 * a stale one answers 409 DEM1002 with `current`. The comparison window
 * ("Record Changed") marks the fields that differ. "Refresh" is the source
 * outcome: the current record replaces the entries. "Re-apply my changes"
 * copies the user's edits onto `current` and reviews again under its version.
 *
 * Session A is the `asMaintenance` fixture; session B is a second browser
 * context opened by `openSignedInPage`, which the test owns and closes in
 * `finally`. Each test creates its own uniquely named customer through the API
 * fixture and checks the stored result through it, touching no seed row and no
 * database. DEM1002 is the source text with its typo corrected ("Review").
 */
import {
  createCustomer,
  expect,
  getCustomer,
  openSignedInPage,
  requireBaseURL,
  test,
  toasts,
  uniqueName,
  USERS,
} from '../fixtures/auth';
import type { APIRequestContext, CustomerResponse, Locator, Page } from '../fixtures/auth';

/**
 * Playwright's `browser` fixture type, taken from the sign-in helper's
 * signature so that every Playwright symbol still comes through the shared
 * fixture module, which does not re-export `Browser` itself.
 */
type Browser = Parameters<typeof openSignedInPage>[0];

/** The edit confirmation notice of a passed review (catalog key DEM0000). */
const DEM0000 = 'Press Enter to update. F12 to Cancel.';

/** The stale-update message (catalog key DEM1002), the source text with "Rewiew" corrected. */
const DEM1002 = 'Someone else changed record. Review data.';

const STORED_CITY = 'SPRINGFIELD';

const A_CITY = 'SHELBYVILLE';

const B_CORP_PHONE = '(217) 555-0199';

function detailDialog(page: Page): Locator {
  return page.getByRole('dialog', { name: /Change Customer/ });
}

/**
 * The DEM1002 comparison window, named by its header "Customer Master" /
 * "Record Changed". It is nested inside the detail window, so it is located by
 * its own name rather than by the DEM1002 text, which the enclosing detail
 * window contains as well.
 */
function conflictDialog(page: Page): Locator {
  return page.getByRole('dialog', { name: /Record Changed/ });
}

/**
 * The input labelled exactly `label` inside `scope`. Exact matching keeps
 * "Name" from also resolving "Account Manager Name".
 */
function field(scope: Locator, label: string): Locator {
  return scope.getByLabel(label, { exact: true });
}

function confirmationPanel(detail: Locator): Locator {
  return detail.getByRole('group', { name: 'Confirm customer' });
}

function listRow(page: Page, name: string): Locator {
  return page.getByRole('row').filter({ has: page.getByLabel(`Option for ${name}`, { exact: true }) });
}

/**
 * The comparison row of one field, matched by its row header exactly, because
 * "Name" is a substring of "Account Manager Name".
 */
function conflictRow(page: Page, conflict: Locator, label: string): Locator {
  return conflict.getByRole('row').filter({ has: page.getByRole('rowheader', { name: label, exact: true }) });
}

/**
 * Asserts one comparison row: the user's value, the current record's value and
 * the "Changed" mark, which is present exactly when the two values differ.
 */
async function expectConflictRow(page: Page, conflict: Locator, label: string, mine: string, current: string): Promise<void> {
  const mark = mine === current ? '' : 'Changed';
  await expect(conflictRow(page, conflict, label).getByRole('cell'), `comparison row "${label}"`).toHaveText([
    mine,
    current,
    mark,
  ]);
}

async function openForEdit(page: Page, filter: string, name: string): Promise<Locator> {
  const nameFilter = page.getByLabel('Name starts with:', { exact: true });
  await nameFilter.fill(filter);
  await nameFilter.press('Enter');

  const option = page.getByLabel(`Option for ${name}`, { exact: true });
  await option.fill('2');
  await option.press('Enter');

  const detail = detailDialog(page);
  await expect(detail).toBeVisible();
  await expect(field(detail, 'Name')).toHaveValue(name);
  const city = field(detail, 'City');
  await expect(city).toBeEditable();
  await expect(city).toHaveValue(STORED_CITY);
  return detail;
}

async function reviewFrom(page: Page, detail: Locator, input: Locator): Promise<Locator> {
  await input.press('Enter');
  await expect(toasts(page).status).toContainText(DEM0000);
  const confirm = confirmationPanel(detail);
  await expect(confirm).toBeVisible();
  return confirm;
}

type ConflictReached = {
  created: CustomerResponse;
  detailB: Locator;
  conflict: Locator;
};

/**
 * Creates a customer, has both sessions open it at version 0, saves A's City
 * change, then confirms B's Corporate Phone change against the stale version.
 * Checks that B's comparison shows DEM1002 with no alert toast and marks City
 * and Corporate Phone "Changed" but not Name, and returns what the test needs
 * to resolve the conflict.
 */
async function reachConflict(
  page: Page,
  pageB: Page,
  api: APIRequestContext,
  tag: string,
): Promise<ConflictReached> {
  const { name, filter } = uniqueName(tag);
  const created = await createCustomer(api, { name, city: STORED_CITY });
  expect(created.city, 'created city').toBe(STORED_CITY);
  expect(created.corpPhone, 'the created phone must differ from session B\'s change').not.toBe(B_CORP_PHONE);

  const detailA = await openForEdit(page, filter, name);
  const detailB = await openForEdit(pageB, filter, name);

  const cityA = field(detailA, 'City');
  await cityA.fill(A_CITY);
  const confirmA = await reviewFrom(page, detailA, cityA);
  await expect(field(confirmA, 'City')).toHaveValue(A_CITY);
  await confirmA.press('Enter');
  await expect(detailA).toBeHidden();

  const afterA = await getCustomer(api, created.custId);
  expect(afterA.version, 'version after session A saved').toBe(1);
  expect(afterA.city, 'city after session A saved').toBe(A_CITY);

  const phoneB = field(detailB, 'Corporate Phone');
  await phoneB.fill(B_CORP_PHONE);
  const confirmB = await reviewFrom(pageB, detailB, phoneB);
  await expect(field(confirmB, 'Corporate Phone')).toHaveValue(B_CORP_PHONE);
  await confirmB.press('Enter');

  const conflict = conflictDialog(pageB);
  await expect(conflict).toBeVisible();
  await expect(conflict).toContainText(DEM1002);
  // The comparison shows DEM1002 itself; the presenter publishes no alert for it.
  await expect(toasts(pageB).alert).not.toContainText(DEM1002);

  await expectConflictRow(pageB, conflict, 'City', STORED_CITY, A_CITY);
  await expectConflictRow(pageB, conflict, 'Corporate Phone', B_CORP_PHONE, created.corpPhone);
  await expectConflictRow(pageB, conflict, 'Name', name, name);

  return { created, detailB, conflict };
}

/**
 * Runs `body` with a second Sales session (session B) on `/customers`, and
 * always closes B's browser context afterwards, also when `body` fails.
 */
async function withSessionB(
  browser: Browser,
  baseURL: string | undefined,
  body: (pageB: Page) => Promise<void>,
): Promise<void> {
  const { context, page } = await openSignedInPage(browser, requireBaseURL(baseURL), USERS.maintenance, '/customers');
  try {
    await body(page);
  } finally {
    await context.close();
  }
}

test.use({ startPath: '/customers' });

test.describe('concurrent edit conflict', () => {
  test('Re-apply my changes merges the edit onto the current record and saves it', async ({
    asMaintenance: page,
    api,
    browser,
    baseURL,
  }) => {
    await withSessionB(browser, baseURL, async (pageB) => {
      const { created, detailB, conflict } = await reachConflict(page, pageB, api, 'CONF REAPPLY');

      await conflict.getByRole('button', { name: 'Re-apply my changes', exact: true }).click();
      await expect(conflict).toBeHidden();
      await expect(toasts(pageB).status).toContainText(DEM0000);
      const confirm = confirmationPanel(detailB);
      await expect(confirm).toBeVisible();
      await expect(field(confirm, 'City')).toHaveValue(A_CITY);
      await expect(field(confirm, 'Corporate Phone')).toHaveValue(B_CORP_PHONE);

      await confirm.press('Enter');
      await expect(detailB).toBeHidden();
      await expect(toasts(pageB).alert).toHaveText('');
      await expect(listRow(pageB, created.name)).toContainText(A_CITY);

      const stored = await getCustomer(api, created.custId);
      expect(stored).toMatchObject({
        city: A_CITY,
        corpPhone: B_CORP_PHONE,
        version: 2,
        chgUser: USERS.maintenance.username,
      });
    });
  });

  test('Refresh loads the current record and saves it as stored', async ({ asMaintenance: page, api, browser, baseURL }) => {
    await withSessionB(browser, baseURL, async (pageB) => {
      const { created, detailB, conflict } = await reachConflict(page, pageB, api, 'CONF REFRESH');

      await conflict.getByRole('button', { name: 'Refresh', exact: true }).click();
      await expect(conflict).toBeHidden();
      const city = field(detailB, 'City');
      await expect(city).toBeEditable();
      await expect(city).toHaveValue(A_CITY);
      await expect(field(detailB, 'Corporate Phone')).toHaveValue(created.corpPhone);

      const confirm = await reviewFrom(pageB, detailB, city);
      await expect(field(confirm, 'City')).toHaveValue(A_CITY);
      await expect(field(confirm, 'Corporate Phone')).toHaveValue(created.corpPhone);
      await confirm.press('Enter');
      await expect(detailB).toBeHidden();
      await expect(toasts(pageB).alert).toHaveText('');
      await expect(listRow(pageB, created.name)).toContainText(A_CITY);

      const stored = await getCustomer(api, created.custId);
      expect(stored).toMatchObject({
        city: A_CITY,
        corpPhone: created.corpPhone,
        version: 2,
        chgUser: USERS.maintenance.username,
      });
    });
  });
});
