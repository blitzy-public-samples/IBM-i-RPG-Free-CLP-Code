/**
 * Messages display: the one place the application shows messages. It replaces
 * the 5250 message subfile, the MSGSFL/MSGCTL records of PMTCUSTD, MTNCUSTD
 * and PMTSTATED, which SndMsgPgmQ filled (QMHSNDPM) and ClrMsgPgmQ emptied
 * (QMHRMVPM) once per screen cycle. Here a message is plain data: a caller
 * publishes an already-formatted text.
 *
 * Lifetime: a toast stays until the next user action. A click anywhere (mouse,
 * or Enter/Space on a button, which the browser turns into a click) clears the
 * list here; a command key (Enter, a function key, Escape, PageUp/PageDown)
 * clears it through `KeyScopeProvider`'s `onBeforeCommand`, which `src/App.tsx`
 * wires to `clear`. Typing text clears nothing, as the 5250 cleared its message
 * subfile per screen cycle, not per keystroke. There is no auto-dismiss timer
 * and no dismiss button.
 *
 * Roles: `status` (polite) carries information and confirmations, such as
 * DEM0000, DEM0009, DEM0002 and DEM0006; `alert` (assertive) carries errors:
 * every problem `detail` and the client-raised DEM0003, DEM0004 and DEM0005.
 * The caller chooses the kind; this file inspects no message code and holds no
 * message text.
 */
import { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react';
import type { ReactNode } from 'react';

/** One displayed message. `id` is unique for the life of the page. */
export type Toast = { id: number; kind: 'status' | 'alert'; text: string };

type ToastMessage = { kind: Toast['kind']; text: string };

type ToastApi = {
  /**
   * Appends a message to the list. Text that is empty or only blanks is
   * ignored, so the region never shows an empty box.
   */
  publish(message: ToastMessage): void;
  /** Removes every message, as ClrMsgPgmQ removed `*ALL` from the queue. */
  clear(): void;
};

/*
 * Two contexts: the stable API and the changing list. A component that calls
 * useToasts() reads only the first, so publishing re-renders the region alone.
 * `null` is the "outside ToastProvider" sentinel; neither context is exported.
 */
const ToastApiContext = createContext<ToastApi | null>(null);
const ToastListContext = createContext<readonly Toast[] | null>(null);

/*
 * Module-level id source. Ids are taken in event handlers (publish), never
 * during render, so a re-render or a StrictMode double render cannot reuse or
 * skip one. A fresh id for every publish gives a republished text a new DOM
 * node, so a screen reader announces it again.
 */
let nextToastId = 0;

/**
 * Owns the message list and renders `children` followed by the only
 * `<ToastRegion />`. Mount it once, above every screen and dialog, so the
 * region sits outside every dialog; `Dialog` keeps that live region out of
 * `inert`. Screens and dialogs never render another host.
 */
export function ToastProvider({ children }: { children: ReactNode }) {
  const [toasts, setToasts] = useState<readonly Toast[]>([]);

  // Functional updates throughout: clear() followed by publish() in the same
  // user action, even from a native listener and then a React handler in one
  // task, leaves exactly the new message.
  const publish = useCallback(({ kind, text }: ToastMessage) => {
    if (text.trim() === '') {
      return;
    }
    // The id is taken here, outside the updater, which React may call twice
    // in StrictMode; the updater itself stays pure.
    nextToastId += 1;
    const toast: Toast = { id: nextToastId, kind, text };
    setToasts((list) => [...list, toast]);
  }, []);

  const clear = useCallback(() => {
    setToasts((list) => (list.length === 0 ? list : []));
  }, []);

  const api = useMemo<ToastApi>(() => ({ publish, clear }), [publish, clear]);

  // Clear on the next click. The capture-phase listener on `document` runs
  // before React's root listeners, so a click handler that publishes (a
  // function-key button raising DEM0003, say) shows its message fresh. Events
  // are deliberately not filtered on `isTrusted`: Testing Library dispatches
  // untrusted events, and a scripted click is still the user's next action.
  useEffect(() => {
    document.addEventListener('click', clear, true);
    return () => {
      document.removeEventListener('click', clear, true);
    };
  }, [clear]);

  return (
    <ToastApiContext value={api}>
      <ToastListContext value={toasts}>
        {children}
        <ToastRegion />
      </ToastListContext>
    </ToastApiContext>
  );
}

/**
 * Returns the stable `{ publish, clear }` API of the enclosing
 * `ToastProvider`.
 *
 * @throws Error when called outside `ToastProvider`.
 */
export function useToasts(): ToastApi {
  const api = useContext(ToastApiContext);
  if (api === null) {
    throw new Error('useToasts must be used inside ToastProvider');
  }
  return api;
}

/**
 * The message host (the MSGSFL/MSGCTL equivalent). `ToastProvider` renders the
 * only instance; nothing else renders it.
 *
 * Both live-region containers are always rendered, even when empty, so
 * assistive technology has registered them before the first message arrives.
 * Each message is keyed by its id, so a text republished after a clear is a
 * new node and is announced again.
 *
 * @throws Error when rendered outside `ToastProvider`.
 */
export function ToastRegion() {
  const toasts = useContext(ToastListContext);
  if (toasts === null) {
    throw new Error('ToastRegion must be rendered inside ToastProvider');
  }

  const status = toasts.filter((toast) => toast.kind === 'status');
  const alerts = toasts.filter((toast) => toast.kind === 'alert');

  return (
    <div className="toast-region">
      <div role="status" aria-live="polite">
        {status.map((toast) => (
          <p key={toast.id} className="toast toast--status">
            {toast.text}
          </p>
        ))}
      </div>
      <div role="alert" aria-live="assertive">
        {alerts.map((toast) => (
          <p key={toast.id} className="toast toast--alert">
            {toast.text}
          </p>
        ))}
      </div>
    </div>
  );
}
