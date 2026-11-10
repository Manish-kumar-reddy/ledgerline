import { useState } from 'react';
import { Link } from 'react-router';
import * as oversightApi from '../api/oversight.js';
import { useAsync } from '../components/useAsync.js';
import { EmptyState, ErrorPanel, Money, Pagination, Skeleton, StatusBadge } from '../components/ui.jsx';
import { shortId } from '../money/money.js';

export default function OversightAccountsPage({ api = oversightApi }) {
  const [page, setPage] = useState(0);
  const { loading, data, error, reload } = useAsync(() => api.listAllAccounts(page, 25), [page]);
  const items = data?.items ?? [];
  return (
    <section>
      <header className="page-header">
        <div>
          <p className="eyebrow">Oversight</p>
          <h1>All accounts</h1>
        </div>
        <Link to="/oversight/reconciliation" className="button button--secondary">Reconciliation</Link>
      </header>
      {loading && <Skeleton rows={5} />}
      <ErrorPanel error={error} onRetry={reload} />
      {!loading && !error && items.length === 0 && <EmptyState title="No accounts yet." />}
      {items.length > 0 && (
        <div className="table-wrap">
          <table className="data-table">
            <thead>
              <tr>
                <th scope="col">Account</th><th scope="col">Type</th><th scope="col">Owner</th>
                <th scope="col">Status</th><th scope="col" className="num">Balance</th><th scope="col"><span className="sr-only">Statement</span></th>
              </tr>
            </thead>
            <tbody>
              {items.map((account) => (
                <tr key={account.id}>
                  <td data-label="Account"><span className="strong">{account.name}</span><span className="statement__note" title={account.id}>{shortId(account.id)}…</span></td>
                  <td data-label="Type">{account.kind === 'SYSTEM' ? <StatusBadge value="SYSTEM" label="Settlement (system)" /> : 'Customer'}</td>
                  <td data-label="Owner" title={account.ownerId ?? ''}>{account.ownerId ? `${shortId(account.ownerId)}…` : '—'}</td>
                  <td data-label="Status"><StatusBadge value={account.status} label={account.status.toLowerCase()} /></td>
                  <td data-label="Balance" className="num"><Money minor={account.balanceMinor} /></td>
                  <td><Link to={`/oversight/accounts/${account.id}`}>Statement →</Link></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      <Pagination page={page} totalPages={data?.totalPages} onChange={setPage} />
    </section>
  );
}
