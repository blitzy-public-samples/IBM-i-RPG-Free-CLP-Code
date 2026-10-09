/**
 * ScreenHeader: the header of every screen and dialog, replacing the 5250
 * `SH_HDR` record of PMTCUSTD, MTNCUSTD and PMTSTATED (the centred title
 * constant, the highlighted function line `SH_FUNCT DSPATR(HI)` and the DDS
 * `USER` keyword).
 *
 * The programs centred the function text with `CenterStr`, which trims it and
 * pads it with leading blanks. That padding is presentation only, so
 * `.screen-header__function` in `src/styles/global.css` centres the text with
 * `text-align: center`, and this component renders it untouched: no padding
 * arithmetic, no non-breaking-space centring and no trimming. The DDS `DATE`
 * and `TIME` fields and `SH_PGM` are 5250 chrome and are not rendered. The
 * source's `--> Bad Parm 1 <--` header has no equivalent, because every mode
 * is known before a screen renders.
 */

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
 * The user name follows a visually hidden "Signed in as " prefix, so screen
 * readers announce what the name is while the visible text matches the 5250
 * `USER` field. Layout comes entirely from the `.screen-header*` grid areas in
 * `src/styles/global.css`, so the DOM order (title, function, user) is also
 * the reading order.
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
