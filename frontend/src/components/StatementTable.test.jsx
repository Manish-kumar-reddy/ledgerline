import { render, screen, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import StatementTable from './StatementTable.jsx';

const ME = 'aaaaaaaa-0000-4000-8000-000000000001';
const SOMEONE = 'bbbbbbbb-0000-4000-8000-000000000002';
const lines = [
  { sequence: 1, status: 'POSTED', operation: 'DEPOSIT', direction: 'CREDIT', amountMinor: 50000, balanceMinor: 50000,
    initiatedBy: ME, counterpartyAccountId: 'a0000000-0000-4000-8000-000000000001', occurredAt: '2026-11-10T10:00:00Z' },
  { sequence: 2, status: 'REFUSED', operation: 'WITHDRAWAL', direction: null, amountMinor: 900000, balanceMinor: 50000,
    reason: 'INSUFFICIENT_FUNDS', initiatedBy: ME, occurredAt: '2026-11-10T10:01:00Z' },
  { sequence: 3, status: 'POSTED', operation: 'TRANSFER', direction: 'CREDIT', amountMinor: 10000, balanceMinor: 60000,
    initiatedBy: SOMEONE, counterpartyAccountId: 'cccccccc-0000-4000-8000-000000000003', occurredAt: '2026-11-10T10:02:00Z' },
];

describe('StatementTable', () => {
  it('shows debits and credits in their own columns with the running balance', () => {
    render(<StatementTable lines={lines} currentUserId={ME} />);
    const deposit = screen.getByTestId('line-1');
    expect(within(deposit).getByText('Deposit')).toBeInTheDocument();
    expect(deposit).toHaveTextContent('₹500.00');
    expect(within(deposit).getByText('Posted')).toBeInTheDocument();
    expect(within(deposit).getByText('You')).toBeInTheDocument();
    const transfer = screen.getByTestId('line-3');
    expect(within(transfer).getByText('Transfer in')).toBeInTheDocument();
    expect(transfer).toHaveTextContent('₹600.00');
    expect(within(transfer).getByText('bbbbbbbb…')).toBeInTheDocument();
  });

  it('marks refused attempts, with their reason, no debit or credit, and the unchanged balance', () => {
    render(<StatementTable lines={lines} currentUserId={ME} />);
    const refused = screen.getByTestId('line-2');
    expect(within(refused).getByText('Refused')).toBeInTheDocument();
    expect(refused).toHaveTextContent('Insufficient funds');
    expect(refused).toHaveTextContent('₹9,000.00 not moved');
    expect(within(refused).getByText('₹500.00')).toBeInTheDocument();     // balance column only
    expect(within(refused).getAllByText('—')).toHaveLength(2);            // no debit, no credit
  });
});
