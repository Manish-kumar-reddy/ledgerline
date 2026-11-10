import { useState } from 'react';
import { Link } from 'react-router';
import * as accountsApi from '../api/accounts.js';
import { useAuth } from '../auth/session.js';
import { useAsync } from '../components/useAsync.js';
import { CopyId, EmptyState, ErrorPanel, Field, Money, Pagination, Skeleton, StatusBadge } from '../components/ui.jsx';

function OpenAccount({ onOpened, api }) {
  const [open, setOpen] = useState(false);
  const [name, setName] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);
  const invalid = !name.trim() || name.trim().length > 100;

  async function submit(event) {
    event.preventDefault();
    if (invalid) return;
    setBusy(true);
    setError(null);
    try {
      await api.createAccount(name.trim());
      setName('');
      setOpen(false);
      onOpened();
    } catch (failure) {
      setError(failure);
    } finally {
      setBusy(false);
    }
  }

  if (!open) {
    return <button type="button" className="button" onClick={() => setOpen(true)}>Open an account</button>;
  }
  return (
    <form className="inline-form" onSubmit={submit} aria-label="Open an account">
      <Field id="account-name" label="Account name" hint="For example: Savings, Rent, Travel">
        <input id="account-name" value={name} maxLength={100} onChange={(e) => setName(e.target.value)} autoFocus />
      </Field>
      <div className="inline-form__actions">
        <button type="submit" className="button" disabled={busy || invalid}>{busy ? 'Opening…' : 'Open account'}</button>
        <button type="button" className="button button--secondary" onClick={() => setOpen(false)}>Cancel</button>
      </div>
      <ErrorPanel error={error} />
    </form>
  );
}

export default function AccountsPage({ api = accountsApi }) {
  const { user } = useAuth();
  const [page, setPage] = useState(0);
  const { loading, data, error, reload } = useAsync(() => api.listAccounts(page, 12), [page]);
  const accounts = data?.items ?? [];
  const pageTotal = accounts.reduce((sum, a) => sum + a.balanceMinor, 0);

  return (
    <section>
      <header className="page-header">
        <div>
          <p className="eyebrow">Welcome, {user.fullName}</p>
          <h1>My accounts</h1>
        </div>
        <OpenAccount onOpened={reload} api={api} />
      </header>

      {data && data.totalItems > 0 && (
        <div className="summary">
          <span className="summary__label">{data.totalPages > 1 ? 'Total on this page' : 'Total balance'}</span>
          <Money minor={pageTotal} className="summary__value" />
          <span className="muted">{data.totalItems} account{data.totalItems === 1 ? '' : 's'}</span>
        </div>
      )}

      {loading && <Skeleton rows={3} />}
      <ErrorPanel error={error} onRetry={reload} />
      {!loading && !error && accounts.length === 0 && (
        <EmptyState title="You have no accounts yet.">
          <p>Open your first account to deposit, withdraw and transfer.</p>
        </EmptyState>
      )}
      <ul className="cards">
        {accounts.map((account) => (
          <li key={account.id} className="card">
            <div className="card__top">
              <Link to={`/accounts/${account.id}`} className="card__name">{account.name}</Link>
              <StatusBadge value={account.status} label={account.status.toLowerCase()} />
            </div>
            <Money minor={account.balanceMinor} className="card__balance" />
            <CopyId id={account.id} />
            <Link to={`/accounts/${account.id}`} className="card__action">View statement and move money →</Link>
          </li>
        ))}
      </ul>
      <Pagination page={page} totalPages={data?.totalPages} onChange={setPage} />
    </section>
  );
}
