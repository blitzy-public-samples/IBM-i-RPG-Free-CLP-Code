/**
 * Browser entry point: mounts `App` under `StrictMode` into `#root`.
 *
 * Stylesheet order is a contract: `tokens.css` declares the CSS custom
 * properties (palette, spacing, type, focus ring), and `global.css`, which
 * consumes them through `var(--…)`, must load after it.
 *
 * `StrictMode` stays on. The providers beneath it tolerate double-invoked
 * effects: the one document key listener and the message-catalog fetch are
 * idempotent.
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
