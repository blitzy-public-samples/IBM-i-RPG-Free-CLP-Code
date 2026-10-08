/**
 * End-to-end flow: the Selection picker embedded in a host form (UC-05).
 *
 * PMTCUSTR's Selection mode served "any in-house program that needed to prompt for a customer id
 * number" [5250_Subfile/README.md:33-40]: header "Selection" and options `1=Select 5=Display` only
 * [5250_Subfile/PMTCUSTR.SQLRPGLE:756-765]; option 1 returned the row's id
 * [5250_Subfile/PMTCUSTR.SQLRPGLE:439-445]; F3 or F12 returned nothing
 * [5250_Subfile/PMTCUSTR.SQLRPGLE:245-249,359-368]. The target's `CustomerPicker` is hosted by the
 * "Order entry" form at `/demo/selection`.
 *
 * Selection is a picker context, not a role, so the flow runs as the Sales user (MAINTENANCE): if
 * the role leaked into the picker, the 2=Edit and F6=Add absence assertions would fail. The host
 * binds F12 to leave for the menu, which is why the cancel step checks that the page is still the
 * host form.
 *
 * The spec only reads the V5 rows AAAG `URNA \NUNC\ COMPANY` and AAID `URNA JUSTO FAUCIBUS PC`
 * (Custmast.sql ids 6 and 291), the only active names starting with `URNA`, so it must run before
 * any generator load, which replaces them. The order of the two rows is not asserted: the ICU
 * collation `customer_sort` decides where the backslash sorts.
 */
import { test, expect, toasts } from '../fixtures/auth';

/** Seed row `AAAG` (Custmast.sql id 6, `NUNC` replaced by `\NUNC\`), as stored and as rendered. */
const NUNC = 'URNA \\NUNC\\ COMPANY';

const NUNC_ID = 'AAAG';

/** Seed row `AAID` (Custmast.sql id 291), the other active name starting with the filter. */
const JUSTO = 'URNA JUSTO FAUCIBUS PC';

const NAME_FILTER = 'URNA';

test.use({ startPath: '/demo/selection' });

test('Selection picker: F4 prompts, option 1 returns the customer id, F12 returns nothing', async ({
  asMaintenance: page,
}) => {
  // The host page is inert while the picker is open but stays in the DOM, so
  // "Customer id +" is matched exactly; the picker has its own "State +".
  const hostField = page.getByLabel('Customer id +', { exact: true });
  const hostHeader = page.getByText('Order entry', { exact: true });
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
    await expect(alerts).toHaveText('');
  });

  await test.step(`Cancelling a new prompt with F12 returns nothing and leaves ${NUNC_ID} in the field`, async () => {
    await hostField.focus();
    await page.keyboard.press('F4');

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
