import { useState } from 'react';
import { Link, useParams } from 'react-router';
import * as accountsApi from '../api/accounts.js';
import * as ledgerApi from '../api/ledger.js';
import { useAuth } from '../auth/session.js';
import MoneyForm from '../components/MoneyForm.jsx';
import StatementTable from '../components/StatementTable.jsx';
import { useAsync } from '../components/useAsync.js';
import { CopyId, EmptyState, ErrorPanel, Money, Pagination, Skeleton, StatusBadge } from '../components/ui.jsx';

const TABS = [['deposit', 'Deposit'], ['withdraw', 'Withdraw'], ['transfer', 'Transfer']];

export default function AccountPage({ accounts = accountsApi, ledger = ledgerApi }) {
  const { id } = useParams();
  const { user } = useAuth();
  const [tab, setTab] = useState('deposit');
  const [page, setPage] = useState(0);
  const account = useAsync(() => accounts.getAccount(id), [id]);
  const statement = useAsync(() => ledger.statement(id, page, 25), [id, page]);

  function refresh() {
    account.reload();
    statement.reload();
  }

  if (account.error?.status === 404) {
    return (
      <EmptyState title="Account not found.">
        <p>It does not exist or is not yours. <Link to="/accounts">Back to my accounts</Link></p>
      </EmptyState>
    );
  }
  const a = account.data;
  return (
    <section>
      <p><Link to="/accounts" className="back">← My accounts</Link></p>
      {account.loading && !a && <Skeleton rows={2} />}
      <ErrorPanel error={account.error} onRetry={account.reload} />
      {a && (
        <header className="account-header">
          <div>
            <h1>{a.name}</h1>
            <div className="account-header__meta">
              <StatusBadge value={a.status} label={a.status.toLowerCase()} />
              <CopyId id={a.id} />
            </div>
          </div>
          <div className="account-header__balance">
            <span className="summary__label">Available balance</span>
            <Money minor={a.balanceMinor} className="account-header__amount" />
          </div>
        </header>
      )}

      {a && (
        <div className="grid">
          <section className="panel panel--plain" aria-labelledby="move-money">
            <h2 id="move-money">Move money</h2>
            <div className="tabs" role="tablist">
              {TABS.map(([key, label]) => (
                <button key={key} type="button" role="tab" aria-selected={tab === key}
                  className={`tabs__tab ${tab === key ? 'tabs__tab--active' : ''}`} onClick={() => setTab(key)}>
                  {label}
                </button>
              ))}
            </div>
            {a.status !== 'ACTIVE' && (
              <p className="panel panel--info">This account is {a.status.toLowerCase()}; money movements will be refused.</p>
            )}
            <MoneyForm key={tab} kind={tab} accountId={a.id} onPosted={refresh} api={ledger} />
          </section>

          <section aria-labelledby="statement">
            <div className="section-header">
              <h2 id="statement">Statement</h2>
              <button type="button" className="button button--ghost" onClick={() => window.print()}>Print</button>
            </div>
            {statement.loading && !statement.data && <Skeleton rows={5} />}
            <ErrorPanel error={statement.error} onRetry={statement.reload} />
            {statement.data && statement.data.lines.length === 0 && (
              <EmptyState title="No activity yet.">
                <p>Deposits, withdrawals, transfers and refused attempts will appear here.</p>
              </EmptyState>
            )}
            {statement.data && statement.data.lines.length > 0 && (
              <>
                <StatementTable lines={statement.data.lines} currentUserId={user.id} />
                <Pagination page={page} totalPages={statement.data.totalPages} onChange={setPage} />
              </>
            )}
          </section>
        </div>
      )}
    </section>
  );
}
