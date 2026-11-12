import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router';
import { describe, expect, it, vi } from 'vitest';
import { AuthContext } from '../auth/session.js';
import AccountPage from './AccountPage.jsx';
import OversightStatementPage from './OversightStatementPage.jsx';

const ME = 'aaaaaaaa-0000-4000-8000-000000000001';
const ACCOUNT = { id: 'acc-1', name: 'Savings', status: 'ACTIVE', kind: 'CUSTOMER', balanceMinor: 6000 };

function line(sequence, status, operation) {
  return { sequence, status, operation, direction: status === 'POSTED' ? 'CREDIT' : null, amountMinor: 100,
    balanceMinor: 6000, reason: status === 'REFUSED' ? 'INSUFFICIENT_FUNDS' : null, initiatedBy: ME,
    occurredAt: '2026-11-12T10:00:00Z' };
}

/** A fake statement endpoint: 3 pages of whatever the filters select. */
function statementApi(fn) {
  return vi.fn(async (_id, page, size, filters = {}) => {
    if (filters.status === 'REFUSED' && filters.operation === 'DEPOSIT') {
      return { account: ACCOUNT, lines: [], page, size, totalLines: 0, totalPages: 0 };
    }
    const status = filters.status || 'POSTED';
    return { account: ACCOUNT, lines: [line(page * 10 + 1, status, filters.operation || 'DEPOSIT')], page, size,
      totalLines: 30, totalPages: 3, ...fn?.(filters) };
  });
}

function renderHolder(ledger) {
  const accounts = { getAccount: vi.fn().mockResolvedValue(ACCOUNT) };
  render(
    <MemoryRouter initialEntries={['/accounts/acc-1']}>
      <AuthContext.Provider value={{ user: { id: ME, role: 'ACCOUNT_HOLDER' }, login: vi.fn(), logout: vi.fn() }}>
        <Routes><Route path="/accounts/:id" element={<AccountPage accounts={accounts} ledger={ledger} />} /></Routes>
      </AuthContext.Provider>
    </MemoryRouter>,
  );
}

function renderOversight(api) {
  render(
    <MemoryRouter initialEntries={['/oversight/accounts/acc-1']}>
      <Routes><Route path="/oversight/accounts/:id" element={<OversightStatementPage api={api} />} /></Routes>
    </MemoryRouter>,
  );
}

const lastCall = (mock) => mock.mock.calls.at(-1);

describe.each([
  ['account holder statement', (statement) => renderHolder({ statement }), 'statement'],
  ['oversight statement', (anyStatement) => renderOversight({ anyStatement }), 'anyStatement'],
])('%s filters', (_name, renderPage) => {
  it('starts unfiltered and sends the chosen filters to the server', async () => {
    const api = statementApi();
    renderPage(api);
    expect(await screen.findByTestId('line-1')).toBeInTheDocument();
    expect(lastCall(api).slice(1)).toEqual([0, 25, { status: '', operation: '' }]);

    await userEvent.selectOptions(screen.getByLabelText('Status'), 'REFUSED');
    await waitFor(() => expect(lastCall(api).slice(1)).toEqual([0, 25, { status: 'REFUSED', operation: '' }]));
    await userEvent.selectOptions(screen.getByLabelText('Type'), 'TRANSFER');
    await waitFor(() => expect(lastCall(api).slice(1)).toEqual([0, 25, { status: 'REFUSED', operation: 'TRANSFER' }]));
    expect(await screen.findByText(/Filtered: Refused · Transfers\. 30 matching lines/)).toBeInTheDocument();
    expect(within(await screen.findByTestId('line-1')).getByText('Refused')).toBeInTheDocument();
  });

  it('goes back to the first page whenever a filter changes', async () => {
    const api = statementApi();
    renderPage(api);
    await screen.findByTestId('line-1');
    await userEvent.click(screen.getByRole('button', { name: 'Next' }));
    await userEvent.click(await screen.findByRole('button', { name: 'Next' }));
    expect(await screen.findByText('Page 3 of 3')).toBeInTheDocument();
    expect(lastCall(api)[1]).toBe(2);

    await userEvent.selectOptions(screen.getByLabelText('Type'), 'WITHDRAWAL');
    await waitFor(() => expect(lastCall(api).slice(1)).toEqual([0, 25, { status: '', operation: 'WITHDRAWAL' }]));
    expect(await screen.findByText('Page 1 of 3')).toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: 'Next' }));
    await waitFor(() => expect(lastCall(api)[1]).toBe(1));
    await userEvent.click(screen.getByRole('button', { name: 'Clear filters' }));
    await waitFor(() => expect(lastCall(api).slice(1)).toEqual([0, 25, { status: '', operation: '' }]));
    expect(screen.queryByText(/Filtered:/)).not.toBeInTheDocument();
  });

  it('says when nothing matches, rather than that there is no activity', async () => {
    const api = statementApi();
    renderPage(api);
    await screen.findByTestId('line-1');
    await userEvent.selectOptions(screen.getByLabelText('Status'), 'REFUSED');
    await userEvent.selectOptions(screen.getByLabelText('Type'), 'DEPOSIT');
    expect(await screen.findByText('No lines match these filters.')).toBeInTheDocument();
    expect(screen.getByText(/0 matching lines/)).toBeInTheDocument();
    expect(screen.queryByText(/No activity/)).not.toBeInTheDocument();
    expect(screen.queryByRole('navigation', { name: 'Pages' })).not.toBeInTheDocument();
  });

  it('offers exactly the values the server accepts', async () => {
    renderPage(statementApi());
    await screen.findByTestId('line-1');
    const values = (label) => [...screen.getByLabelText(label).options].map((option) => option.value);
    expect(values('Status')).toEqual(['', 'POSTED', 'REFUSED']);
    expect(values('Type')).toEqual(['', 'DEPOSIT', 'WITHDRAWAL', 'TRANSFER']);
  });

  it('shows a rejected filter (400) as an error, not as an empty statement', async () => {
    const api = statementApi();
    renderPage(api);
    await screen.findByTestId('line-1');
    api.mockRejectedValueOnce({ status: 400, title: 'Bad Request', detail: 'Invalid request parameters.' });
    await userEvent.selectOptions(screen.getByLabelText('Status'), 'POSTED');
    expect(await screen.findByText('Bad Request')).toBeInTheDocument();
    expect(screen.queryByText('No lines match these filters.')).not.toBeInTheDocument();
  });
});
