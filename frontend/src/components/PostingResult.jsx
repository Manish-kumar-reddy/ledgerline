import { Money, StatusBadge } from './ui.jsx';
import { REFUSAL_REASONS } from './labels.js';

const DONE = { DEPOSIT: 'Deposit posted', WITHDRAWAL: 'Withdrawal posted', TRANSFER: 'Transfer posted' };

export default function PostingResult({ result }) {
  if (!result) return null;
  if (result.outcome === 'REFUSED') {
    return (
      <div className="panel panel--refused" role="status" data-testid="posting-result">
        <StatusBadge value="REFUSED" label="Refused" />
        <strong>{REFUSAL_REASONS[result.reason] ?? result.reason}</strong>
        <p>Nothing was moved. Your balance is still <Money minor={result.balanceMinor} />. The attempt is recorded on your statement.</p>
        {result.replayed && <p className="muted">This request had already been processed; this is its original result.</p>}
      </div>
    );
  }
  return (
    <div className="panel panel--success" role="status" data-testid="posting-result">
      <StatusBadge value="POSTED" label="Posted" />
      <strong>{DONE[result.operation] ?? 'Posted'}: <Money minor={result.amountMinor} /></strong>
      <p>New balance <Money minor={result.balanceMinor} />.</p>
      {result.replayed && <p className="muted">This request had already been processed; nothing was posted twice.</p>}
    </div>
  );
}
