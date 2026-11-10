import { Money, StatusBadge } from './ui.jsx';
import { shortId } from '../money/money.js';
import { REFUSAL_REASONS } from './labels.js';

const dateTime = new Intl.DateTimeFormat('en-IN', { dateStyle: 'medium', timeStyle: 'medium' });

function describe(line) {
  if (line.operation === 'TRANSFER') {
    return line.direction === 'CREDIT' ? 'Transfer in' : 'Transfer out';
  }
  return { DEPOSIT: 'Deposit', WITHDRAWAL: 'Withdrawal' }[line.operation] ?? line.operation;
}

/** Every posted debit/credit and every refused attempt, in the account's sequence order, with running balance. */
export default function StatementTable({ lines, currentUserId }) {
  return (
    <div className="table-wrap">
      <table className="statement">
        <thead>
          <tr>
            <th scope="col">#</th>
            <th scope="col">Date and time</th>
            <th scope="col">Description</th>
            <th scope="col" className="num">Debit</th>
            <th scope="col" className="num">Credit</th>
            <th scope="col" className="num">Balance</th>
            <th scope="col">Status</th>
            <th scope="col">Initiated by</th>
          </tr>
        </thead>
        <tbody>
          {lines.map((line) => {
            const refused = line.status === 'REFUSED';
            return (
              <tr key={line.sequence} className={refused ? 'statement__row--refused' : ''} data-testid={`line-${line.sequence}`}>
                <td data-label="#">{line.sequence}</td>
                <td data-label="Date and time">{dateTime.format(new Date(line.occurredAt))}</td>
                <td data-label="Description">
                  <span className="statement__what">{describe(line)}</span>
                  {line.description && <span className="statement__note">{line.description}</span>}
                  {line.counterpartyAccountId && (
                    <span className="statement__note" title={line.counterpartyAccountId}>
                      {line.direction === 'CREDIT' ? 'from' : 'to'} {shortId(line.counterpartyAccountId)}…
                    </span>
                  )}
                </td>
                <td data-label="Debit" className="num">
                  {!refused && line.direction === 'DEBIT' ? <Money minor={line.amountMinor} className="money--debit" /> : <span className="muted">—</span>}
                </td>
                <td data-label="Credit" className="num">
                  {!refused && line.direction === 'CREDIT' ? <Money minor={line.amountMinor} className="money--credit" /> : <span className="muted">—</span>}
                </td>
                <td data-label="Balance" className="num"><Money minor={line.balanceMinor} /></td>
                <td data-label="Status">
                  {refused ? (
                    <>
                      <StatusBadge value="REFUSED" label="Refused" />
                      <span className="statement__note">
                        {REFUSAL_REASONS[line.reason] ?? line.reason} · <Money minor={line.amountMinor} /> not moved
                      </span>
                    </>
                  ) : <StatusBadge value="POSTED" label="Posted" />}
                </td>
                <td data-label="Initiated by" title={line.initiatedBy}>
                  {line.initiatedBy === currentUserId ? 'You' : `${shortId(line.initiatedBy)}…`}
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}
