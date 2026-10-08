/**
 * Error presentation (client): the one hook that turns a failed API call into
 * what the user sees.
 *
 * The client half of the error model (AAP 0.4.5) is split by ownership:
 * - `api/client.ts` (transport) parses every non-2xx response into
 *   `ApiError {status, problem}`, calls the `onUnauthorized` handler on 401
 *   and rejects. It renders nothing.
 * - This hook (presentation) shows the problem: one alert toast, plus the
 *   field highlight and focus when the caller asks for them, or the conflict
 *   dialog for a stale update.
 * - The owning feature keeps every piece of state (the draft, the field
 *   errors, whether `ConflictCompareDialog` is open) and passes its setters
 *   in {@link PresentOptions}. This hook holds none.
 *
 * What it replaces. On the 5250 `SndSflMsg` (5250_Subfile/PMTCUSTR.SQLRPGLE,
 * 5250_Subfile/MTNCUSTR.SQLRPGLE) sent a CUSTMSGF message id plus its data to
 * the message subfile, and each `Edit_SD_*` procedure set the field's
 * `RI_` (reverse image) and `PC_` (position cursor) indicators in the same
 * step (5250_Subfile/MTNCUSTD.DSPF). Here `publish` is the message,
 * `setFieldErrors` the reverse image and `focusField` the cursor, all in one
 * synchronous `present` call so the message and the highlight arrive
 * together. UpdateRecd's DEM1002 branch showed the message and re-read the
 * row; here `onConflict(problem.current)` opens the compare dialog, which
 * shows the DEM1002 text itself. SQLProblem's escape message with SQLSTATE,
 * SQL text and a dump (Service_Pgms/SRV_SQL.SQLRPGLE) has no counterpart: the
 * user sees only the DEM9999 text, never anything internal.
 *
 * Layer rule: imports `react`, `../api/problem`, `../components/ToastRegion`
 * and `../messages/MessageCatalogProvider` only; never `features/`, `auth/`,
 * `keyboard/`, `api/client.ts` or the generated schema.
 *
 * Test guidance: a component that calls this hook renders inside a
 * `QueryClientProvider`, `MessageCatalogProvider` and `ToastProvider`, and
 * reads the alert through `within(screen.getByRole('alert'))`.
 */
import { useCallback, useMemo } from 'react';
import { fieldErrors, isApiError, syntheticProblem } from '../api/problem';
import type { FieldError, Problem } from '../api/problem';
import { useToasts } from '../components/ToastRegion';
import { useMessages } from '../messages/MessageCatalogProvider';

/**
 * The customer as now stored, carried by a 409 DEM1002 problem's `current`:
 * the schema `CustomerResponse` (the nine customer fields plus `custId`,
 * `chgTime`, `chgUser` and `version`). Derived from {@link Problem} so this
 * module never imports the generated schema; it is the same type that
 * `api/customers.ts` exports as `CustomerResponse`, so a feature handler
 * typed `(current: CustomerResponse) => void` is assignable to `onConflict`.
 */
export type ConflictRecord = NonNullable<Problem['current']>;

/**
 * The owning feature's setters. Every member is optional; a caller that
 * passes none gets the alert toast only.
 */
export type PresentOptions = {
  /**
   * Receives the problem's `errors`, in server order, when it names at least
   * one field. Field names are passed through unchanged: the JSON property
   * names (`name`, `addr`, `city`, `state`, `zip`, `corpPhone`, `acctMgr`,
   * `acctPhone`, `active`) or the filter names (`name`, `city`, `state`,
   * `nameContains`).
   */
  setFieldErrors?: (errors: FieldError[]) => void;
  /**
   * Receives the first field of `errors`, after `setFieldErrors`. Called only
   * when `setFieldErrors` is passed too; on its own it does nothing.
   */
  focusField?: (field: string) => void;
  /**
   * Receives `current` of a 409 DEM1002 problem instead of an alert toast,
   * because `ConflictCompareDialog` shows the DEM1002 text itself.
   */
  onConflict?: (current: ConflictRecord) => void;
};

/** What {@link useProblemPresenter} returns; stable while the catalog is unchanged. */
export type ProblemPresenter = {
  /**
   * Shows `error` to the user, routed by the rules of
   * {@link useProblemPresenter}. Never throws, logs or re-throws.
   *
   * @param error the caught value: normally an `ApiError`; anything else is
   *   shown as DEM9999
   * @param options the owning feature's setters
   */
  present: (error: unknown, options?: PresentOptions) => void;
};

/** The catalog key shown for anything that is not an API problem. */
const PROGRAM_ERROR_CODE = 'DEM9999';

/** The status and code of a stale update, whose problem carries `current`. */
const CONFLICT_STATUS = 409;
const CONFLICT_CODE = 'DEM1002';

/** Whether a value is a string holding at least one non-blank character. */
function hasText(value: unknown): value is string {
  return typeof value === 'string' && value.trim() !== '';
}

/**
 * Returns `present`, which shows a failed call's problem (error model,
 * AAP 0.4.5; the SndSflMsg message plus RI/PC field indicators of the 5250).
 *
 * Routing, in this order, within one synchronous call:
 * 1. **Resolve.** An `ApiError` supplies its `problem` and `status`. Any other
 *    value (a programming error, a thrown string, `undefined`) becomes a
 *    synthetic DEM9999 with status 500; its message and stack are never shown.
 * 2. **Conflict.** A 409 DEM1002 that carries `current`, presented with
 *    `onConflict`, calls `onConflict(current)` and stops: no toast, no field
 *    callbacks. Without `onConflict`, or without `current`, it continues.
 * 3. **Alert.** One `alert` toast with the problem's `detail`; when the detail
 *    is blank (the synthetic DEM9999 of `api/client.ts`, whose bundle holds no
 *    message text), the catalog text of `code` with `args` substituted.
 * 4. **Fields.** When `setFieldErrors` is passed and the problem names fields,
 *    `setFieldErrors(errors)` in server order, then `focusField` with the first
 *    field. Without `setFieldErrors` the alert is all that is shown.
 *
 * A 401 or 403 that reaches `present` is an ordinary alert (APP0401, APP0403):
 * the transport has already handled sign-out. `stateAccepted` is not read
 * here; the detail dialog reads it from the `ApiError` itself.
 *
 * @example
 * ```ts
 * const { present } = useProblemPresenter();
 * try {
 *   await customersApi.review(request);
 * } catch (error) {
 *   present(error, { setFieldErrors, focusField, onConflict: openCompare });
 * }
 * ```
 *
 * @throws Error when rendered outside `ToastProvider` or
 *   `MessageCatalogProvider` (a wiring mistake).
 */
export function useProblemPresenter(): ProblemPresenter {
  const { publish } = useToasts();
  const { format } = useMessages();

  const present = useCallback(
    (error: unknown, options?: PresentOptions): void => {
      // 1. Resolve the problem. Only an ApiError's own problem is trusted;
      //    anything else is a fault in the page, shown as the program error.
      const problem: Problem = isApiError(error) ? error.problem : syntheticProblem(500);
      const status = isApiError(error) ? error.status : 500;

      // 2. Conflict hand-off: the compare dialog shows the DEM1002 text and
      //    the stored record, so nothing else is published or highlighted.
      const onConflict = options?.onConflict;
      const current = problem.current;
      if (
        status === CONFLICT_STATUS &&
        problem.code === CONFLICT_CODE &&
        onConflict !== undefined &&
        current !== undefined &&
        current !== null
      ) {
        onConflict(current);
        return;
      }

      // 3. Exactly one alert. The server's `detail` is already the catalog
      //    text with its arguments substituted; a blank one (synthetic
      //    problem) is resolved here from the catalog, which until it loads
      //    returns the code itself, so the alert is never empty.
      const code = hasText(problem.code) ? problem.code : PROGRAM_ERROR_CODE;
      const args = Array.isArray(problem.args) ? problem.args.map(String) : [];
      const text = hasText(problem.detail) ? problem.detail : format(code, args);
      publish({ kind: 'alert', text });

      // 4. Field highlight, then focus on the first field at fault: the
      //    reverse-image and position-cursor indicators of the 5250 edit.
      const setFieldErrors = options?.setFieldErrors;
      const errors = fieldErrors(problem);
      if (setFieldErrors === undefined || errors.length === 0) {
        return;
      }
      setFieldErrors(errors);
      const [first] = errors;
      if (first !== undefined) {
        options?.focusField?.(first.field);
      }
    },
    [publish, format],
  );

  return useMemo<ProblemPresenter>(() => ({ present }), [present]);
}
