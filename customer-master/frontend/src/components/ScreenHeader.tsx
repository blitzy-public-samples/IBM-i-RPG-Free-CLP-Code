/**
 * ScreenHeader: the header of every screen and dialog.
 *
 * It replaces the 5250 `SH_HDR` record that PMTCUSTD, MTNCUSTD and PMTSTATED
 * each declare:
 *
 *   - row 1, the centred constant `'Customer Master'` (`'USA States'` in the
 *     state window), rendered as the `<h1>` title;
 *   - row 2, the highlighted function line `SH_FUNCT DSPATR(HI)`, rendered as
 *     the function paragraph;
 *   - row 2, the DDS `USER` keyword, rendered as the signed-in user.
 *
 * The 5250 programs centred the function text with `CenterStr`, which pads
 * the trimmed string with leading blanks. That padding is presentation only,
 * so it is replaced by `text-align: center` on `.screen-header__function` in
 * `src/styles/global.css`; this component passes the text through untouched,
 * with no padding arithmetic, no non-breaking-space centring and no trimming.
 *
 * The DDS `DATE` and `TIME` fields and the `SH_PGM` program name are 5250
 * chrome and are not rendered.
 *
 * The component is purely presentational: it calls no hooks, reads no
 * context and holds no message text. Callers supply the function text
 * (`Inquiry`, `Maintenance` or `Selection` for the search screen;
 * `Displaying Customer`, `Change Customer` or `Add Customer` for the detail
 * dialog) and the signed-in user (from `AuthProvider` / `GET /api/session`).
 * The only literal is the default title, the DDS constant `'Customer Master'`;
 * the state picker overrides it with `title="USA States"`. The source's
 * `--> Bad Parm 1 <--` header has no equivalent, because every mode is known
 * before a screen renders.
 *
 * Layer rule: components import nothing from `api/`, `errors/` or
 * `features/`. This file needs only the React JSX runtime.
 *
 * @example
 * // Search page in Maintenance mode
 * <ScreenHeader functionText="Maintenance" user={session.username} />
 *
 * @example
 * // Detail dialog, named by its header through Dialog's labelledBy
 * <Dialog labelledBy="customer-detail-title customer-detail-function" …>
 *   <ScreenHeader id="customer-detail" functionText="Change Customer" user={user} />
 * </Dialog>
 * // accessible name: "Customer Master Change Customer"
 */

/** Props of {@link ScreenHeader}. */
export interface ScreenHeaderProps {
  /**
   * Row-1 title. Defaults to the DDS constant `'Customer Master'`; the state
   * picker passes `'USA States'`.
   */
  title?: string;
  /**
   * Row-2 function line (`SH_FUNCT`), for example `Inquiry` or
   * `Change Customer`. Rendered as given and centred by CSS.
   */
  functionText: string;
  /**
   * Signed-in username (the DDS `USER` keyword). Nothing is rendered for
   * that line when it is absent or empty, such as before the session loads.
   */
  user?: string;
  /**
   * Optional id prefix. When set, the title receives `${id}-title` and the
   * function line `${id}-function`, so a dialog can name itself with
   * `aria-labelledby="${id}-title ${id}-function"`. Without it neither
   * element carries an id.
   */
  id?: string;
}

/**
 * Renders the screen header: a `<header>` holding the `<h1>` title, the
 * highlighted function line and, when known, the signed-in user. The user
 * name sits in its own `<span>` after a visually hidden "Signed in as "
 * prefix, so screen readers announce what the name is while the visible text
 * matches the 5250 `USER` field.
 *
 * Layout comes entirely from the `.screen-header*` classes in
 * `src/styles/global.css`, which place each child by grid area, so the DOM
 * order here (title, function, user) is also the reading order.
 */
export function ScreenHeader({
  title = 'Customer Master',
  functionText,
  user,
  id,
}: ScreenHeaderProps) {
  // An empty-string id is treated like an absent one, so no element ever
  // receives an id such as "-title" that another dialog could also produce.
  const titleId = id ? `${id}-title` : undefined;
  const functionId = id ? `${id}-function` : undefined;

  return (
    <header className="screen-header">
      <h1 className="screen-header__title" id={titleId}>
        {title}
      </h1>
      <p className="screen-header__function" id={functionId}>
        {functionText}
      </p>
      {user ? (
        <p className="screen-header__user">
          <span className="visually-hidden">Signed in as </span>
          <span>{user}</span>
        </p>
      ) : null}
    </header>
  );
}
