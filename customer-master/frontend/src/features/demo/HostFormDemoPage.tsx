/**
 * HostFormDemoPage: the "Order entry" demo screen, at route `/demo/selection`,
 * that hosts the embeddable customer picker.
 *
 * What it stands in for. PMTCUSTR's third mode, Selection, "could be used for
 * any in-house program that needed to prompt for a customer id number"
 * [5250_Subfile/README.md:33-40]. Such a program called PMTCUSTR with mode `S`
 * and a return parameter; Selection exists only when that parameter is passed
 * [5250_Subfile/PMTCUSTR.SQLRPGLE:756-759], and option 1 moves the chosen id
 * into it and ends [5250_Subfile/PMTCUSTR.SQLRPGLE:439-445]. This page is that
 * calling program, reduced to the one field that receives the id:
 *
 *   Calling program + PMTCUSTR `S`                Here
 *   a "Customer id +" field, F4 to prompt          `FormField` "Customer id +" and
 *                                                    F4, the F4=Prompt+ button or
 *                                                    the "Look up customer" button
 *   CALL PMTCUSTR ('S' : CustId)                   `<CustomerPicker open>`
 *   option 1 returns the id in CustId              `onSelect(custId)` writes the field
 *   F3 / F12 in PMTCUSTR: CustId stays cleared     `onCancel()` returns nothing; the
 *     [5250_Subfile/PMTCUSTR.SQLRPGLE:245-249]       field keeps what it held
 *   F4 off a promptable field: DEM0005             the same, from the catalog
 *     [5250_Subfile/PMTCUSTR.SQLRPGLE:383-386]
 *   any other key: DEM0003                         the same, from the catalog
 *
 * Cancel keeps the field as it was. The source clears its return slot on
 * entry and sets it only on option 1, so a cancelled prompt hands back a
 * blank id; the picker contract returns no value at all on cancel, so a host
 * has nothing to write and leaves its own field untouched.
 *
 * What it deliberately does not do. It holds no business logic, makes no API
 * call of its own (the picker's search calls `GET /api/customers`), submits
 * no order and does not validate a typed id: the user may type an id
 * directly, as into any 5250 input field. The field group is a plain `<div>`,
 * not a `<form>`, and Enter is left unbound, so Enter can never submit
 * anything. Selection is a picker context open to every signed-in user, not a
 * role, so the page asks for none; `src/routes.tsx` renders it behind sign-in.
 *
 * Keyboard (the keyboard scope contract of `KeyScopeProvider`):
 * - One scope binds F3 and F12 (Escape is delivered as F12) to leave for the
 *   menu at `/`, and F4 to prompt. Every other function key shows the DEM0003
 *   alert and changes nothing. Enter, PageUp and PageDown are unbound and keep
 *   their native behaviour.
 * - The scope is inactive while the picker is open. The picker's own scope
 *   (registered by its search panel) is then topmost, so F12 or Escape there
 *   cancels the picker only and never reaches this page's F12 binding.
 * - No `keydown` listener is added here; the provider owns the only one.
 *   `KeyScopeProvider`'s `onBeforeCommand` and `ToastProvider`'s click
 *   listener clear earlier messages, so this page never clears toasts itself.
 *
 * Focus. F4 prompts only while focus is on the Customer id field or on the
 * "Look up customer" button, the two controls that belong to the promptable
 * field. The F4=Prompt+ legend button does not take focus on mouse-down, so a
 * click on it while the field has focus prompts too. `Dialog` returns focus
 * to the element that opened the picker when it closes, which is the button
 * after a button press; this page then moves focus on to the field, so after
 * a selection or a cancel the cursor is always back in "Customer id +", as a
 * 5250 prompt returned the cursor to the prompted field.
 *
 * Constraints:
 * - Message texts come only from the catalog (`format('DEM0003')`,
 *   `format('DEM0005')`); the literals here are screen labels and legends.
 * - Presentation uses the existing `.screen`, `.instructions` and
 *   `.footer-brand` classes of `src/styles/global.css` and the shared
 *   components' own classes; no component library is used.
 * - Layer rule: imports come only from `features/customers/CustomerPicker`,
 *   `components/`, `keyboard/`, `messages/`, `auth/`, React and
 *   `react-router-dom`; nothing from `api/` or `errors/`.
 * - Rendering requires, above it: `QueryClientProvider`, a router,
 *   `AuthProvider`, `MessageCatalogProvider`, `ToastProvider` and
 *   `KeyScopeProvider`, the providers the picker's search panel needs.
 */
import { useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useAuth } from '../../auth/AuthProvider';
import { FormField } from '../../components/FormField';
import { FunctionKeyBar } from '../../components/FunctionKeyBar';
import type { FunctionKeyBarItem } from '../../components/FunctionKeyBar';
import { ScreenHeader } from '../../components/ScreenHeader';
import { useToasts } from '../../components/ToastRegion';
import { useFunctionKeys } from '../../keyboard/useFunctionKeys';
import { useMessages } from '../../messages/MessageCatalogProvider';
import { CustomerPicker } from '../customers/CustomerPicker';

/**
 * Id prefix of this screen's header: the title gets `host-form-title` and the
 * function line `host-form-function`, which also names the field group.
 */
const HEADER_ID = 'host-form';

/**
 * Id of the Customer id input. A fixed id is safe because this screen is the
 * only element of its route and is never mounted twice.
 */
const CUSTOMER_ID_FIELD = 'order-custid';

/** Length of the customer id: the 4-character base-36 CUSTID (SD_CUSTID 4A). */
const CUSTOMER_ID_LENGTH = 4;

/**
 * Renders the Order entry host form for the signed-in user. Takes no props:
 * the only state is the Customer id field and whether the picker is open.
 */
export function HostFormDemoPage() {
  const navigate = useNavigate();
  const { publish } = useToasts();
  const { format } = useMessages();
  const { username } = useAuth();

  /** The "Customer id +" field: the calling program's return slot. */
  const [custId, setCustId] = useState('');
  /** Whether the Selection-mode picker is shown. */
  const [pickerOpen, setPickerOpen] = useState(false);

  const inputRef = useRef<HTMLInputElement>(null);
  const lookupButtonRef = useRef<HTMLButtonElement>(null);
  // Set when the picker is closed by a selection or a cancel, and cleared by
  // the focus effect below once focus is back in the field. Written in
  // handlers and read in the effect only, never during render.
  const refocusFieldRef = useRef(false);

  // Focus after the picker closes. Dialog's own cleanup returns focus to its
  // invoker (the lookup button when that opened it) synchronously while the
  // picker unmounts; React runs every unmount cleanup of a commit before the
  // passive effects it creates, so this effect runs after that focus return
  // and the field wins. Nothing is set as state here.
  useEffect(() => {
    if (pickerOpen || !refocusFieldRef.current) {
      return;
    }
    refocusFieldRef.current = false;
    inputRef.current?.focus();
  }, [pickerOpen]);

  /** Shows the picker; it starts a fresh Selection-mode search each time. */
  function openPicker(): void {
    setPickerOpen(true);
  }

  /**
   * F4 and the F4=Prompt+ button: prompts only from the promptable field (the
   * Customer id input or its lookup button); anywhere else DEM0005, with
   * nothing changed.
   */
  function prompt(): void {
    const active = document.activeElement;
    if (active !== null && (active === inputRef.current || active === lookupButtonRef.current)) {
      openPicker();
      return;
    }
    publish({ kind: 'alert', text: format('DEM0005') });
  }

  /** Option 1 in the picker: the chosen id fills the field and the picker closes. */
  function handleSelect(selected: string): void {
    setCustId(selected);
    refocusFieldRef.current = true;
    setPickerOpen(false);
  }

  /** F3, F12 or Escape in the picker: it closes and the field keeps its value. */
  function handleCancel(): void {
    refocusFieldRef.current = true;
    setPickerOpen(false);
  }

  /** F3 and F12 (Escape): leave the calling program for the menu. */
  function exit(): void {
    void navigate('/');
  }

  /** Any function key this screen does not enable: DEM0003, nothing else. */
  function keyNotActive(): void {
    publish({ kind: 'alert', text: format('DEM0003') });
  }

  // One handler per key, shared by the key binding and its legend button. A
  // fresh bindings object on each render is safe: the scope reads the latest
  // handlers at keydown time.
  useFunctionKeys({ F3: exit, F4: prompt, F12: exit }, { onUnbound: keyNotActive, active: !pickerOpen });

  const keys: FunctionKeyBarItem[] = [
    { key: 'F3', label: 'F3=Exit', onPress: exit },
    { key: 'F4', label: 'F4=Prompt+', onPress: prompt },
    { key: 'F12', label: 'F12=Cancel', onPress: exit },
  ];

  return (
    <main className="screen">
      <ScreenHeader functionText="Order entry" user={username ?? undefined} id={HEADER_ID} />
      <div role="group" aria-labelledby={`${HEADER_ID}-function`}>
        <FormField
          id={CUSTOMER_ID_FIELD}
          label="Customer id +"
          value={custId}
          onChange={setCustId}
          maxLength={CUSTOMER_ID_LENGTH}
          uppercase
          inputRef={inputRef}
          autoComplete="off"
          size={CUSTOMER_ID_LENGTH}
        />
        {/* Its name avoids "Prompt", "Select", "Display", "Exit" and "Cancel",
            so role queries for the legend buttons and the picker's row
            buttons stay unambiguous. */}
        <button type="button" ref={lookupButtonRef} aria-haspopup="dialog" onClick={() => openPicker()}>
          Look up customer
        </button>
      </div>
      <p className="instructions">Type a customer id, or press F4 in the field to look one up.</p>
      <footer>
        <p className="footer-brand">Demo Corp of America</p>
        <FunctionKeyBar keys={keys} />
      </footer>
      <CustomerPicker open={pickerOpen} onSelect={handleSelect} onCancel={handleCancel} />
    </main>
  );
}
