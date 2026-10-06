// ESLint flat configuration for the Customer Master frontend (ESLint 10).
//
// `npm run lint` runs `eslint .` from this folder. The file is plain ESM
// because package.json declares "type": "module"; it is not type-checked by
// `tsc -b` (tsconfig.node.json covers vite.config.ts only).
//
// Scope of the rule set:
// - Correctness rules only: @eslint/js recommended, typescript-eslint
//   recommended and the react-hooks recommended preset. No Prettier and no
//   formatting rules; layout is not a lint concern here.
// - No type-aware preset (`recommendedTypeChecked`), so linting needs no
//   TypeScript program and stays fast; `tsc -b` in `npm run build` already
//   enforces `strict` and `noUncheckedIndexedAccess`.
import js from '@eslint/js';
import globals from 'globals';
import reactHooks from 'eslint-plugin-react-hooks';
import tseslint from 'typescript-eslint';
import { defineConfig, globalIgnores } from 'eslint/config';

export default defineConfig([
  // Build output, coverage reports and the generated OpenAPI types are never
  // linted. src/api/schema.d.ts is produced by `npm run gen:api` and checked
  // byte for byte by `git diff --exit-code`, so no lint run (and no --fix)
  // may touch it. node_modules is ignored by ESLint by default.
  globalIgnores(['dist', 'coverage', 'src/api/schema.d.ts']),

  // Application, test and tooling sources written in TypeScript. They run in
  // the browser (or in jsdom under Vitest), hence the browser globals.
  {
    name: 'customer-master/typescript',
    files: ['**/*.{ts,tsx}'],
    extends: [
      js.configs.recommended,
      tseslint.configs.recommended,
      // eslint-plugin-react-hooks 7.x exposes its flat preset under
      // `configs.flat.recommended`: rules-of-hooks, exhaustive-deps and the
      // React Compiler diagnostics.
      reactHooks.configs.flat.recommended,
    ],
    languageOptions: {
      ecmaVersion: 2022,
      globals: globals.browser,
    },
    rules: {
      // A leading underscore marks a parameter or variable that is
      // intentionally unused, such as a positional callback argument.
      '@typescript-eslint/no-unused-vars': [
        'error',
        { argsIgnorePattern: '^_', varsIgnorePattern: '^_' },
      ],
    },
  },

  // Tooling configuration files execute in Node, not in the browser.
  {
    name: 'customer-master/node-tooling',
    files: ['vite.config.ts', 'eslint.config.js'],
    languageOptions: {
      globals: globals.node,
    },
  },
]);
