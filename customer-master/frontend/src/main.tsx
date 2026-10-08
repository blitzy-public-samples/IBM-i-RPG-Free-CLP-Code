/**
 * Browser entry point of the Customer Master SPA.
 *
 * What it replaces. On IBM i a menu called PMTCUSTR with its mode; the
 * program opened its display file and ran `Init` each time it was entered
 * [5250_Subfile/PMTCUSTR.SQLRPGLE:706-767]. Here the browser loads
 * `index.html`, which loads this module, and React mounts the provider tree
 * in `App` once into `<div id="root">`. Screens then mount and unmount beneath
 * it, which is the program lifecycle in web terms.
 *
 * Stylesheet order is a contract: `tokens.css` declares the CSS custom
 * properties (palette, spacing, type, focus ring, inactive red, the
 * reverse-image error colours), and `global.css`, which consumes them through
 * `var(--…)`, must load after it.
 *
 * `StrictMode` stays on. The providers beneath it tolerate double-invoked
 * effects: the one document key listener and the message-catalog fetch are
 * idempotent.
 *
 * Deliberately nothing else lives here: providers belong to `App.tsx`, the
 * key listener to `KeyScopeProvider`, message texts to the catalog served by
 * `GET /api/messages`; there is no service worker and no analytics.
 */
import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import './styles/tokens.css';
import './styles/global.css';
import { App } from './App';

const rootElement = document.getElementById('root');

// index.html must carry the mount point. Failing loudly beats rendering
// nothing: a missing element means the page shell and this entry disagree.
if (rootElement === null) {
  throw new Error('Missing #root element');
}

createRoot(rootElement).render(
  <StrictMode>
    <App />
  </StrictMode>,
);
