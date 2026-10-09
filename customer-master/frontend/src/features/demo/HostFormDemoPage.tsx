/**
 * HostFormDemoPage: the "Order entry" screen at `/demo/selection` that hosts
 * CustomerPicker. It stands in for an in-house program that called PMTCUSTR
 * with mode `S` and a return parameter [5250_Subfile/README.md:33-40],
 * [5250_Subfile/PMTCUSTR.SQLRPGLE:756-759].
 *
 * PMTCUSTR clears that return slot on entry
 * [5250_Subfile/PMTCUSTR.SQLRPGLE:245-249] and sets it only on option 1
 * [5250_Subfile/PMTCUSTR.SQLRPGLE:439-445]. So option 1 writes the id into
 * "Customer id +", and a cancel (F3, F12 or Escape in the picker) returns
 * nothing and the field keeps its value.
 *
 * Focus. The screen opens with the cursor in "Customer id +", its first input
 * field. After option 1 the cursor is back in "Customer id +", which now holds
 * the id, as a 5250 prompt returned the cursor to the prompted field. After a
 * cancel, focus returns to the control that opened the picker: the field when
 * F4 or F4=Prompt+ prompted from it, the "Look up customer" button when it was
 * clicked or held focus for F4 or F4=Prompt+.
 *
 * F4, or the F4=Prompt+ button (it takes no focus when clicked), prompts only
 * while focus is on the field or the "Look up customer" button; anywhere else
 * it shows DEM0005 [5250_Subfile/PMTCUSTR.SQLRPGLE:383-386]. This page's key
 * scope is inactive while the picker is open, so the picker's scope is topmost
 * and F3, F12 or Escape cancels only the picker.
 *
 * The field group is a `<div>`, not a `<form>`, and Enter is unbound, so
 * nothing is ever submitted. Selection is a picker context open to any
 * signed-in user, not a role.
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

/** Header id prefix; `host-form-function` also names the field group. */
const HEADER_ID = 'host-form';

/** A fixed input id is safe: this screen is its route's only element and is never mounted twice. */
const CUSTOMER_ID_FIELD = 'order-custid';

/** The 4-character base-36 CUSTID. */
const CUSTOMER_ID_LENGTH = 4;

/** The two controls that open the picker: "Customer id +" and "Look up customer". */
type PickerInvoker = 'field' | 'lookup';

/** Route component of `/demo/selection`; takes no props. */
export function HostFormDemoPage() {
  const navigate = useNavigate();
  const { publish } = useToasts();
  const { format } = useMessages();
  const { username } = useAuth();

  const [custId, setCustId] = useState('');
  const [pickerOpen, setPickerOpen] = useState(false);

  const inputRef = useRef<HTMLInputElement>(null);
  const lookupButtonRef = useRef<HTMLButtonElement>(null);
  // The control that opened the picker, set as it opens. Written in handlers,
  // read only in handlers.
  const invokerRef = useRef<PickerInvoker>('field');
  // Where focus goes once the picker has closed: the field after a selection,
  // the invoker after a cancel; null while no close is pending. Written in
  // handlers, read and cleared only in the focus effect.
  const returnFocusRef = useRef<PickerInvoker | null>(null);

  // The cursor on open: "Customer id +", the screen's first input field, as a
  // 5250 display positions it. This effect only moves focus.
  useEffect(() => {
    inputRef.current?.focus();
  }, []);

  // Dialog's cleanup returns focus to the element that had it when the picker
  // opened, synchronously while the picker unmounts. React runs a commit's
  // unmount cleanups before the passive effects it creates, so this effect
  // runs after that focus return and has the last word. It names the target
  // itself rather than trusting that return: a browser that does not focus a
  // clicked button leaves focus where it was when "Look up customer" is
  // clicked, so Dialog may hold the field or nothing instead of the button.
  useEffect(() => {
    const target = returnFocusRef.current;
    if (pickerOpen || target === null) {
      return;
    }
    returnFocusRef.current = null;
    (target === 'field' ? inputRef.current : lookupButtonRef.current)?.focus();
  }, [pickerOpen]);

  function openPicker(invoker: PickerInvoker): void {
    invokerRef.current = invoker;
    setPickerOpen(true);
  }

  function prompt(): void {
    const active = document.activeElement;
    if (active !== null && active === inputRef.current) {
      openPicker('field');
      return;
    }
    if (active !== null && active === lookupButtonRef.current) {
      openPicker('lookup');
      return;
    }
    publish({ kind: 'alert', text: format('DEM0005') });
  }

  function handleSelect(selected: string): void {
    setCustId(selected);
    returnFocusRef.current = 'field';
    setPickerOpen(false);
  }

  function handleCancel(): void {
    returnFocusRef.current = invokerRef.current;
    setPickerOpen(false);
  }

  function exit(): void {
    void navigate('/');
  }

  function keyNotActive(): void {
    publish({ kind: 'alert', text: format('DEM0003') });
  }

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
        <button type="button" ref={lookupButtonRef} aria-haspopup="dialog" onClick={() => openPicker('lookup')}>
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
