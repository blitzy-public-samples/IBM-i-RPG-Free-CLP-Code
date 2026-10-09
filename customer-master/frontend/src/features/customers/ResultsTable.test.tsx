/**
 * Component tests for the column structure of {@link ResultsTable}: the
 * `<colgroup>` that fixes the PMTCUSTD columns, the Actions column inputs for
 * each mode's buttons, the cells kept on one line, and the scroll box the
 * table sits in, which sets the compact rows and, on the search page,
 * reserves a full page of rows. Behaviour of the list itself (options,
 * paging, modes) is covered through the search panel in
 * `CustomerSearchPage.test.tsx`.
 */
import { render, screen, within } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import type { CustomerSummaryResponse } from '../../api/customers';
import { ResultsTable } from './ResultsTable';
import type { RowOption } from './ResultsTable';

const ROWS: CustomerSummaryResponse[] = [
  { custId: '18R7', name: 'QALONG INTERNATIONAL HOLDINGS CC LIMITED', city: 'SAN FRANCISCOX', state: 'CA', zip5: '95112', active: 'Y' },
  { custId: '18R9', name: 'QA LONG CITY CUSTOMER', city: 'TWENTYCHARACTERCITYX', state: 'TX', zip5: '75001', active: 'N' },
];

function renderTable(allowedOptions: RowOption[], rows: CustomerSummaryResponse[] = ROWS) {
  return renderTableView(allowedOptions, rows).table;
}

function renderTableView(allowedOptions: RowOption[], rows: CustomerSummaryResponse[]) {
  const { unmount } = render(
    <ResultsTable
      rows={rows}
      options={{}}
      invalid={{}}
      allowedOptions={allowedOptions}
      onOptionChange={vi.fn()}
      onAction={vi.fn()}
    />,
  );
  return { table: screen.getByRole('table', { name: 'Customers' }), unmount };
}

describe('ResultsTable columns', () => {
  it('fixes the six PMTCUSTD columns in a colgroup, Name without a width of its own', () => {
    const table = renderTable(['5']);

    expect(table).toHaveClass('results-table', 'results-table--fixed', 'results-table--customers');
    const columns = Array.from(table.querySelectorAll('colgroup > col'));
    expect(columns.map((column) => column.getAttribute('class'))).toEqual([
      'col--opt',
      null,
      'col--city',
      'col--st',
      'col--zip',
      'col--actions',
    ]);
    expect(within(table).getAllByRole('columnheader').map((heading) => heading.textContent)).toEqual([
      'Opt',
      'Customer Name',
      'City',
      'St',
      'ZIP',
      'Actions',
    ]);
  });

  it.each([
    { mode: 'Inquiry', allowed: ['5'] as RowOption[], count: '1', label: '3.75rem' },
    { mode: 'Maintenance', allowed: ['2', '5'] as RowOption[], count: '2', label: '6rem' },
    { mode: 'Selection', allowed: ['5', '1'] as RowOption[], count: '2', label: '7rem' },
  ])('sizes Actions for the $mode buttons, with or without rows', ({ allowed, count, label }) => {
    for (const rows of [ROWS, []]) {
      const { table, unmount } = renderTableView(allowed, rows);

      expect(table.style.getPropertyValue('--actions-count')).toBe(count);
      expect(table.style.getPropertyValue('--actions-label')).toBe(label);
      unmount();
    }
  });

  it('keeps St, ZIP and the row buttons on one line, marks the Name cell, and lets names and cities wrap', () => {
    const table = renderTable(['2', '5']);

    const [, first] = within(table).getAllByRole('row');
    if (first === undefined) {
      throw new Error('The table shows no data row');
    }
    const cells = within(first).getAllByRole('cell');
    // cell--name wraps like City by default; the unframed search page keeps
    // each name on one line through it (global.css .results-table--customers).
    expect(cells.map((cell) => cell.className)).toEqual([
      '',
      'cell--name',
      '',
      'cell--nowrap',
      'cell--nowrap',
      'cell--actions',
    ]);
    expect(within(cells[5] as HTMLElement).getAllByRole('button')).toHaveLength(2);
  });

  it('sits in a scroll box whose region name is the caption', () => {
    const table = renderTable(['5']);

    const box = table.parentElement;
    expect(box).toHaveClass('scroll-region');
    // The box sets the compact rows and, on the search page, reserves a full
    // page of rows, empty or not (global.css .customer-list).
    expect(box).toHaveClass('customer-list');
    const caption = table.querySelector('caption');
    expect(caption).toHaveTextContent('Customers');
    expect(caption?.id).not.toBe('');
    // Plain while it fits (jsdom measures no overflow), so it adds no tab stop.
    expect(box).not.toHaveAttribute('tabindex');
  });
});
