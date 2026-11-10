import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import ReconciliationPage from './ReconciliationPage.jsx';

const base = { accounts: 3, journalTransactions: 4, refusedAttempts: 1, customerBalanceTotalMinor: 30000,
  settlementBalanceMinor: -30000, totalBalanceMinor: 0 };

describe('ReconciliationPage', () => {
  it('shows balanced books with the totals', async () => {
    const api = { reconciliation: vi.fn().mockResolvedValue({ ...base, consistent: true, differenceCount: 0, differences: [] }) };
    render(<ReconciliationPage api={api} />);
    expect(await screen.findByText('Books balanced')).toBeInTheDocument();
    expect(screen.getByText('₹300.00')).toBeInTheDocument();
    expect(screen.getByText('−₹300.00')).toBeInTheDocument();
  });

  it('lists differences when the books do not reconcile', async () => {
    const api = { reconciliation: vi.fn().mockResolvedValue({ ...base, consistent: false, differenceCount: 1,
      differences: [{ check: 'ACCOUNT_BALANCE', subjectId: 'acc-1', expected: 500, actual: 600 }] }) };
    render(<ReconciliationPage api={api} />);
    expect(await screen.findByText('1 difference found')).toBeInTheDocument();
    expect(screen.getByText('Stored balance differs from credits − debits')).toBeInTheDocument();
  });

  it('shows a 403 as not permitted rather than signing out', async () => {
    const api = { reconciliation: vi.fn().mockRejectedValue({ status: 403, title: 'Forbidden' }) };
    render(<ReconciliationPage api={api} />);
    expect(await screen.findByText('Forbidden')).toBeInTheDocument();
  });
});
