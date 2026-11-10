import * as oversightApi from '../api/oversight.js';
import { useAsync } from '../components/useAsync.js';
import { ErrorPanel, Money, Skeleton } from '../components/ui.jsx';

const CHECKS = {
  ACCOUNT_BALANCE: 'Stored balance differs from credits − debits',
  ACCOUNT_SEQUENCE: 'Statement numbering differs from line count',
  RUNNING_BALANCE: 'Running balance differs on a ledger entry',
  REFUSED_BALANCE: 'Refused attempt reports a wrong balance',
  JOURNAL_BALANCE: 'Journal debits differ from credits',
  ENTRY_WITHOUT_LINE: 'Ledger entry missing from the statement',
  REFUSAL_WITHOUT_LINE: 'Refused attempt missing from the statement',
  TOTAL_BALANCE: 'All balances do not sum to zero',
};

export default function ReconciliationPage({ api = oversightApi }) {
  const { loading, data, error, reload } = useAsync(() => api.reconciliation(), []);
  return (
    <section>
      <header className="page-header">
        <div>
          <p className="eyebrow">Oversight</p>
          <h1>Reconciliation</h1>
          <p className="muted">Recomputed from every ledger entry, refused attempt and statement line.</p>
        </div>
        <button type="button" className="button button--secondary" onClick={reload} disabled={loading}>
          {loading ? 'Checking…' : 'Run again'}
        </button>
      </header>
      {loading && !data && <Skeleton rows={4} />}
      <ErrorPanel error={error} onRetry={reload} />
      {data && (
        <>
          <div className={`verdict ${data.consistent ? 'verdict--ok' : 'verdict--bad'}`} role="status">
            <span className="verdict__icon" aria-hidden="true">{data.consistent ? '✓' : '!'}</span>
            <div>
              <strong>{data.consistent ? 'Books balanced' : `${data.differenceCount} difference${data.differenceCount === 1 ? '' : 's'} found`}</strong>
              <p>{data.consistent ? 'Every balance matches its ledger entries, and all balances sum to zero.' : 'Investigate the differences below.'}</p>
            </div>
          </div>
          <dl className="figures">
            <div><dt>Customer balances</dt><dd><Money minor={data.customerBalanceTotalMinor} /></dd></div>
            <div><dt>Settlement account</dt><dd><Money minor={data.settlementBalanceMinor} /></dd></div>
            <div><dt>Sum of all balances</dt><dd><Money minor={data.totalBalanceMinor} /></dd></div>
            <div><dt>Accounts</dt><dd>{data.accounts.toLocaleString('en-IN')}</dd></div>
            <div><dt>Journal transactions</dt><dd>{data.journalTransactions.toLocaleString('en-IN')}</dd></div>
            <div><dt>Refused attempts</dt><dd>{data.refusedAttempts.toLocaleString('en-IN')}</dd></div>
          </dl>
          {data.differences.length > 0 && (
            <div className="table-wrap">
              <table className="data-table">
                <thead><tr><th scope="col">Check</th><th scope="col">Subject</th><th scope="col" className="num">Expected</th><th scope="col" className="num">Actual</th></tr></thead>
                <tbody>
                  {data.differences.map((d, i) => (
                    <tr key={`${d.check}-${d.subjectId}-${i}`}>
                      <td data-label="Check">{CHECKS[d.check] ?? d.check}</td>
                      <td data-label="Subject"><code>{d.subjectId ?? 'all accounts'}</code></td>
                      <td data-label="Expected" className="num">{d.expected}</td>
                      <td data-label="Actual" className="num">{d.actual}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </>
      )}
    </section>
  );
}
