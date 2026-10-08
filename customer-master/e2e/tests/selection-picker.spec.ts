/**
 * End-to-end flow: the Selection picker embedded in a host form (UC-05).
 *
 * What it covers. PMTCUSTR's third mode, Selection, served "any in-house
 * program that needed to prompt for a customer id number"
 * [5250_Subfile/README.md:33-40]. The calling program passed mode `S` and a
 * return slot; Init then showed the header "Selection" and the options
 * `1=Select 5=Display` only [5250_Subfile/PMTCUSTR.SQLRPGLE:756-765], option 1
 * moved the row's id into the slot and ended the program
 * [5250_Subfile/PMTCUSTR.SQLRPGLE:439-445], and F3 or F12 left with nothing
 * selected [5250_Subfile/PMTCUSTR.SQLRPGLE:245-249,359-368]. The target
 * replaces that call with the `CustomerPicker` component, hosted here by the
 * "Order entry" demo form at `/demo/selection`:
 *
 *   Calling program + PMTCUSTR `S`            This spec
 *   F4 on the host's "Customer id +" field     focus the field, press F4
 *   header "Selection", 1=Select 5=Display     dialog named "… Selection", that
 *                                                legend, no 2=Edit, no Edit or
 *                                                F6=Add button
 *   first page waits for Enter (:243-256)      no rows until Enter
 *   option 1 returns SF_CUST_H                 option 1 on URNA \NUNC\ COMPANY
 *                                                writes AAAG into the host field
 *   F12 returns nothing                        F12 closes the picker; the field
 *                                                keeps AAAG
 *
 * Selection is a picker context, not a role, so the picker offers 1 and 5
 * whatever the signed-in user may do elsewhere. The flow therefore runs as the
 * Sales user (role MAINTENANCE): if the role leaked into the picker, the
 * Maintenance options (2=Edit, F6=Add) would appear and the assertions below
 * would fail.
 *
 * Keys follow the keyboard scope contract: F4 prompts only while focus is on
 * "Customer id +"; while the picker is open its scope is the topmost one, so
 * Enter and F12 reach the picker alone. The host binds F12 to leave for the
 * menu, which is why the cancel step also checks that the page is still the
 * host form.
 *
 * Data. The spec is read-only and depends on two V5 seed rows (Custmast.sql
 * ids 6 and 291), the only active customers whose names start with `URNA`:
 * `AAAG` = `URNA \NUNC\ COMPANY` (PITTSBURGH, PA) and `AAID` =
 * `URNA JUSTO FAUCIBUS PC` (PHOENIX, AZ). The inactive `NULLA INTEGER URNA
 * INDUSTRIES` does not start with `URNA`, and the default search excludes
 * inactive rows anyway. It must run before any generator load, which replaces
 * the seed rows. The order of the two rows is not asserted: the ICU collation
 * `customer_sort` decides where the backslash sorts.
 *
 * Sign-in happens once, through the fixture's guard redirect of `startPath`.
 * The credentials live in page memory only, so the spec never navigates with
 * `page.goto` or reloads; it moves on by keys alone.
 */
import { test, expect, toasts } from '../fixtures/auth';

/** Seed row `AAAG` (Custmast.sql id 6, `NUNC` replaced by `\NUNC\`), as stored and as rendered. */
const NUNC = 'URNA \\NUNC\\ COMPANY';

/** The id the host field must receive when `NUNC` is selected. */
const NUNC_ID = 'AAAG';

/** Seed row `AAID` (Custmast.sql id 291), the other active name starting with the filter. */
const JUSTO = 'URNA JUSTO FAUCIBUS PC';

/** The "Name starts with:" filter that finds exactly `NUNC` and `JUSTO` among the active seed rows. */
const NAME_FILTER = 'URNA';

test.use({ startPath: '/demo/selection' });

test('Selection picker: F4 prompts, option 1 returns the customer id, F12 returns nothing', async ({
  asMaintenance: page,
}) => {
  // The host page is inert while the picker is open but stays in the DOM, so
  // "Customer id +" is matched exactly; the picker has its own "State +".
  const hostField = page.getByLabel('Customer id +', { exact: true });
  const hostHeader = page.getByText('Order entry', { exact: true });
  // Named by its ScreenHeader: "Customer Master" + "Selection".
  const picker = page.getByRole('dialog', { name: /Selection/ });
  const nameFilter = picker.getByLabel('Name starts with:', { exact: true });
  const dataRows = picker.getByRole('table').locator('tbody > tr');
  const alerts = toasts(page).alert;

  await test.step('F4 on "Customer id +" opens the picker in Selection mode, offering options 1 and 5 only', async () => {
    await expect(hostHeader).toBeVisible();
    await expect(hostField).toHaveValue('');

    await hostField.focus();
    await page.keyboard.press('F4');

    await expect(picker).toBeVisible();
    await expect(picker.getByText('Selection', { exact: true })).toBeVisible();
    await expect(picker.getByText('1=Select 5=Display', { exact: true })).toBeVisible();
    await expect(picker.getByText('2=Edit')).toHaveCount(0);
    await expect(picker.getByRole('button', { name: /Edit/ })).toHaveCount(0);
    await expect(picker.getByRole('button', { name: 'F6=Add', exact: true })).toHaveCount(0);

    // The window opens on its first editable field, and Selection, like
    // Maintenance, searches only on the first Enter.
    await expect(nameFilter).toBeFocused();
    await expect(nameFilter).toHaveValue('');
    await expect(dataRows).toHaveCount(0);
  });

  await test.step(`Searching "${NAME_FILTER}" and option 1 on ${NUNC} writes ${NUNC_ID} into the host field`, async () => {
    await nameFilter.fill(NAME_FILTER);
    await nameFilter.press('Enter');

    await expect(dataRows).toHaveCount(2);
    const nuncRow = dataRows.filter({ hasText: NUNC });
    await expect(nuncRow).toHaveCount(1);
    await expect(dataRows.filter({ hasText: JUSTO })).toHaveCount(1);

    // The backslashes render literally: cell 0 is Opt, then Name, City, St.
    const nuncCells = nuncRow.getByRole('cell');
    await expect(nuncCells.nth(1)).toHaveText(NUNC);
    await expect(nuncCells.nth(2)).toHaveText('PITTSBURGH');
    await expect(nuncCells.nth(3)).toHaveText('PA');

    // Each row offers Select and Display; no row offers Edit, whatever the role.
    for (const name of [NUNC, JUSTO]) {
      await expect(picker.getByRole('button', { name: `Select ${name}`, exact: true })).toBeVisible();
      await expect(picker.getByRole('button', { name: `Display ${name}`, exact: true })).toBeVisible();
    }
    await expect(picker.getByRole('button', { name: /Edit/ })).toHaveCount(0);

    const option = picker.getByLabel(`Option for ${NUNC}`, { exact: true });
    await option.fill('1');
    await option.press('Enter');

    await expect(picker).toBeHidden();
    await expect(hostField).toHaveValue(NUNC_ID);
    await expect(hostField).toBeFocused();
    // The Enter that selected raised no message (no DEM0004, no DEM0003).
    await expect(alerts).toHaveText('');
  });

  await test.step(`Cancelling a new prompt with F12 returns nothing and leaves ${NUNC_ID} in the field`, async () => {
    await hostField.focus();
    await page.keyboard.press('F4');

    // Each opening starts afresh: blank criteria, no list.
    await expect(picker).toBeVisible();
    await expect(nameFilter).toBeFocused();
    await expect(nameFilter).toHaveValue('');
    await expect(dataRows).toHaveCount(0);

    await page.keyboard.press('F12');

    await expect(picker).toBeHidden();
    await expect(hostField).toHaveValue(NUNC_ID);
    await expect(hostField).toBeFocused();
    // F12 reached the picker's scope only: the host's own F12 (exit to the
    // menu) did not run, so the host form is still shown.
    await expect(page).toHaveURL(/\/demo\/selection$/);
    await expect(hostHeader).toBeVisible();
    await expect(alerts).toHaveText('');
  });
});
