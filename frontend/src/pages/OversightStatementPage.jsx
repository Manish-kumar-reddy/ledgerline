import { useState } from 'react';
import { Link, useParams } from 'react-router';
import * as oversightApi from '../api/oversight.js';
import StatementFilters, { FilterSummary } from '../components/StatementFilters.jsx';
import { NO_FILTERS, isFiltered } from '../components/statementFilterOptions.js';
import StatementTable from '../components/StatementTable.jsx';
import { useAsync } from '../components/useAsync.js';
import { CopyId, EmptyState, ErrorPanel, Money, Pagination, Skeleton, StatusBadge } from '../components/ui.jsx';

export default function OversightStatementPage({ api = oversightApi }) {
  const { id } = useParams();
  const [page, setPage] = useState(0);
  const [filters, setFilters] = useState(NO_FILTERS);
  const { loading, data, error, reload } = useAsync(() => api.anyStatement(id, page, 25, filters),
    [id, page, filters.status, filters.operation]);

  function changeFilters(next) {
    setFilters(next);
    setPage(0);
  }

  if (error?.status === 404) {
    return <EmptyState title="Account not found."><p><Link to="/oversight/accounts">Back to all accounts</Link></p></EmptyState>;
  }
  const account = data?.account;
  return (
    <section>
      <p><Link to="/oversight/accounts" className="back">← All accounts</Link></p>
      {loading && !data && <Skeleton rows={5} />}
      <ErrorPanel error={error} onRetry={reload} />
      {account && (
        <header className="account-header">
          <div>
            <p className="eyebrow">{account.kind === 'SYSTEM' ? 'Settlement account (system)' : 'Customer account'} · read-only</p>
            <h1>{account.name}</h1>
            <div className="account-header__meta">
              <StatusBadge value={account.status} label={account.status.toLowerCase()} />
              <CopyId id={account.id} />
            </div>
          </div>
          <div className="account-header__balance">
            <span className="summary__label">Balance</span>
            <Money minor={account.balanceMinor} className="account-header__amount" />
          </div>
        </header>
      )}
      {account && <StatementFilters value={filters} onChange={changeFilters} idPrefix="oversight-statement" />}
      {data && <FilterSummary filters={filters} totalLines={data.totalLines} />}
      {data && data.lines.length === 0 && (
        <EmptyState title={isFiltered(filters) ? 'No lines match these filters.' : 'No activity on this account.'} />
      )}
      {data && data.lines.length > 0 && (
        <>
          <StatementTable lines={data.lines} currentUserId={null} />
          <Pagination page={page} totalPages={data.totalPages} onChange={setPage} />
        </>
      )}
    </section>
  );
}
