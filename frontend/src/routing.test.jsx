import { render, screen, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import { describe, expect, it, vi } from 'vitest';
import { AppRoutes } from './App.jsx';
import { AuthContext } from './auth/session.js';
import { navigationFor } from './components/navigation.js';

vi.mock('./api/accounts.js', () => ({
  listAccounts: vi.fn().mockResolvedValue({ items: [], page: 0, size: 12, totalItems: 0, totalPages: 0 }),
  getAccount: vi.fn(), createAccount: vi.fn(),
}));
vi.mock('./api/oversight.js', () => ({
  listAllAccounts: vi.fn().mockResolvedValue({ items: [], page: 0, size: 25, totalItems: 0, totalPages: 0 }),
  anyStatement: vi.fn(),
  reconciliation: vi.fn().mockResolvedValue({ consistent: true, differenceCount: 0, differences: [], accounts: 1,
    journalTransactions: 0, refusedAttempts: 0, customerBalanceTotalMinor: 0, settlementBalanceMinor: 0, totalBalanceMinor: 0 }),
}));

const users = {
  ACCOUNT_HOLDER: { id: 'u1', fullName: 'Asha Rao', role: 'ACCOUNT_HOLDER' },
  ADMIN: { id: 'u2', fullName: 'Admin User', role: 'ADMIN' },
  AUDITOR: { id: 'u3', fullName: 'Audit User', role: 'AUDITOR' },
};

function renderAt(path, user) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <AuthContext.Provider value={{ user, login: vi.fn(), logout: vi.fn() }}>
        <AppRoutes />
      </AuthContext.Provider>
    </MemoryRouter>,
  );
}

describe('role-based navigation (UI only; the backend authorizes every request)', () => {
  it('lists only what each role can use', () => {
    expect(navigationFor('ACCOUNT_HOLDER').map((n) => n.label)).toEqual(['My accounts']);
    expect(navigationFor('ADMIN').map((n) => n.label)).toEqual(['All accounts', 'Reconciliation']);
    expect(navigationFor('AUDITOR').map((n) => n.label)).toEqual(['All accounts', 'Reconciliation']);
    expect(navigationFor(undefined)).toEqual([]);
  });

  it('sends a signed-out visitor to sign in', () => {
    renderAt('/accounts', null);
    expect(screen.getByRole('heading', { name: 'Sign in' })).toBeInTheDocument();
  });

  it('starts an account holder on their accounts, without oversight links', async () => {
    renderAt('/', users.ACCOUNT_HOLDER);
    expect(await screen.findByRole('heading', { name: 'My accounts' })).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Reconciliation' })).not.toBeInTheDocument();
    expect(screen.queryByText(/Read-only oversight/)).not.toBeInTheDocument();
  });

  it.each(['ADMIN', 'AUDITOR'])('starts %s on read-only oversight with no money actions', async (role) => {
    renderAt('/', users[role]);
    expect(await screen.findByRole('heading', { name: 'All accounts' })).toBeInTheDocument();
    expect(screen.getByText(/Read-only oversight/)).toBeInTheDocument();
    expect(within(screen.getByRole('navigation', { name: 'Main' })).getByRole('link', { name: 'Reconciliation' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Open an account|Deposit|Withdraw/ })).not.toBeInTheDocument();
  });

  it('shows "Not permitted" when a role opens a page it cannot use', () => {
    renderAt('/oversight/reconciliation', users.ACCOUNT_HOLDER);
    expect(screen.getByText('Not permitted')).toBeInTheDocument();
    renderAt('/accounts', users.AUDITOR);
    expect(screen.getAllByText('Not permitted').length).toBeGreaterThan(0);
  });
});
