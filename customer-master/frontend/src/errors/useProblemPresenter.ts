/**
 * Error presentation (client): the one hook that turns a failed API call into
 * what the user sees.
 *
 * `api/client.ts` parses every non-2xx response into `ApiError` and handles
 * 401; this hook only presents. The owning feature keeps all state and passes
 * its setters in {@link PresentOptions}; this hook holds none.
 *
 * Layer rule: imports only `react`, `../api/problem`,
 * `../components/ToastRegion` and `../messages/MessageCatalogProvider`.
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
   * {@link useProblemPresenter}. The value presented never makes it throw:
   * an `ApiError` or anything else ends as one alert toast or as the
   * conflict hand-off, and is neither logged nor re-thrown.
   *
   * @param error the caught value: normally an `ApiError`; anything else is
   *   shown as DEM9999
   * @param options the owning feature's setters
   * @throws whatever one of the caller's own callbacks (`onConflict`,
   *   `setFieldErrors`, `focusField`) throws; it is not caught, so it reaches
   *   the caller of `present` and a defect in the owning feature stays visible.
   */
  present: (error: unknown, options?: PresentOptions) => void;
};

const PROGRAM_ERROR_CODE = 'DEM9999';

const CONFLICT_STATUS = 409;
const CONFLICT_CODE = 'DEM1002';

function hasText(value: unknown): value is string {
  return typeof value === 'string' && value.trim() !== '';
}

/**
 * Returns `present`, which shows a failed call's problem (error model,
 * AAP 0.4.5; the SndSflMsg message plus RI/PC field indicators of the 5250).
 *
 * Routing, in this order, within one synchronous call:
 * 1. **Resolve.** An `ApiError` supplies its `problem` and `status`; anything
 *    else becomes a synthetic DEM9999 with status 500, whose message and stack
 *    are never shown.
 * 2. **Conflict.** A 409 DEM1002 that carries `current`, presented with
 *    `onConflict`, calls `onConflict(current)` and stops: no toast, no field
 *    callbacks. Otherwise it continues.
 * 3. **Alert.** One `alert` toast with the problem's `detail`, or, when that is
 *    blank, the catalog text of `code` with `args`. The catalog returns the
 *    code until it loads, so the alert is never empty.
 * 4. **Fields.** With `setFieldErrors` and a problem that names fields,
 *    `setFieldErrors(errors)` in server order, then `focusField` with the
 *    first field.
 *
 * A 401 or 403 is an ordinary alert (APP0401, APP0403); the transport has
 * already handled a 401's sign-out. `stateAccepted` is not read here; the
 * detail dialog reads it from the `ApiError`.
 *
 * @throws Error when rendered outside `ToastProvider` or
 *   `MessageCatalogProvider` (a wiring mistake).
 */
export function useProblemPresenter(): ProblemPresenter {
  const { publish } = useToasts();
  const { format } = useMessages();

  const present = useCallback(
    (error: unknown, options?: PresentOptions): void => {
      const problem: Problem = isApiError(error) ? error.problem : syntheticProblem(500);
      const status = isApiError(error) ? error.status : 500;

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

      const code = hasText(problem.code) ? problem.code : PROGRAM_ERROR_CODE;
      const args = Array.isArray(problem.args) ? problem.args.map(String) : [];
      const text = hasText(problem.detail) ? problem.detail : format(code, args);
      publish({ kind: 'alert', text });

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
