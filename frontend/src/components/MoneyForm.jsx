import { useRef, useState } from 'react';
import { IdempotencyKeys } from '../api/idempotency.js';
import * as ledgerApi from '../api/ledger.js';
import { MAX_OPERATION_MINOR, isValidAmount, toMinor } from '../money/money.js';
import { ErrorPanel, Field, Money } from './ui.jsx';
import PostingResult from './PostingResult.jsx';

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

const LABELS = {
  deposit: { title: 'Deposit', verb: 'Deposit', hint: 'Simulated cash in. No real bank is involved.' },
  withdraw: { title: 'Withdraw', verb: 'Withdraw', hint: 'Simulated cash out. You cannot withdraw more than your balance.' },
  transfer: { title: 'Transfer', verb: 'Send', hint: 'To another active customer account, by its account ID.' },
};

/**
 * Deposit, withdrawal or transfer. One Idempotency-Key per intended operation: a retry after a network error or a
 * busy/5xx response resends the SAME key (so it can never be posted twice); any change to the form, or a new
 * operation after a definite result, gets a new key.
 */
export default function MoneyForm({ kind, accountId, onPosted, api = ledgerApi, keys: injectedKeys }) {
  const keys = useRef(injectedKeys ?? new IdempotencyKeys());
  const [amount, setAmount] = useState('');
  const [toAccountId, setToAccountId] = useState('');
  const [description, setDescription] = useState('');
  const [touched, setTouched] = useState(false);
  const [confirming, setConfirming] = useState(false);
  const [busy, setBusy] = useState(false);
  const [result, setResult] = useState(null);
  const [error, setError] = useState(null);
  const label = LABELS[kind];

  const amountText = amount.trim();
  const amountError = !isValidAmount(amountText)
    ? 'Enter a positive amount in rupees, with at most 2 decimals (for example 1500.50).'
    : toMinor(amountText) > MAX_OPERATION_MINOR ? 'The most you can move in one operation is ₹10,00,000.00.' : null;
  const destination = toAccountId.trim().toLowerCase();
  const destinationError = kind !== 'transfer' ? null
    : !UUID.test(destination) ? 'Paste the full account ID of the recipient.'
      : destination === accountId.toLowerCase() ? 'Choose a different account to send to.' : null;
  const descriptionError = description.length > 140 ? 'At most 140 characters.' : null;
  const invalid = Boolean(amountError || destinationError || descriptionError);

  function edit(setter) {
    return (event) => {
      setter(event.target.value);
      setConfirming(false);
    };
  }

  function request() {
    const body = { amount: amountText, description: description.trim() || undefined };
    return kind === 'transfer' ? { ...body, toAccountId: destination } : body;
  }

  function review(event) {
    event.preventDefault();
    setTouched(true);
    setResult(null);
    if (invalid) return;
    if (kind === 'deposit') {
      submit();
    } else {
      setConfirming(true);
    }
  }

  async function submit() {
    const body = request();
    const key = keys.current.keyFor({ kind, accountId, ...body });
    setBusy(true);
    setError(null);
    try {
      const call = kind === 'deposit' ? api.deposit : kind === 'withdraw' ? api.withdraw : api.transfer;
      const outcome = await call(accountId, body, key);
      keys.current.settle(null);
      setResult(outcome);
      setConfirming(false);
      if (outcome.outcome === 'POSTED') {
        setAmount('');
        setDescription('');
        setToAccountId('');
        setTouched(false);
      }
      onPosted?.(outcome);
    } catch (failure) {
      keys.current.settle(failure);
      setError(failure);
    } finally {
      setBusy(false);
    }
  }

  const retryable = Boolean(error?.retryable);
  const shownError = error?.status === 409
    ? { ...error, detail: 'This request key was already used with different details. Nothing was posted; please submit again.' }
    : error;
  return (
    <form className="money-form" onSubmit={review} noValidate aria-label={label.title}>
      <p className="money-form__hint">{label.hint}</p>
      {kind === 'transfer' && (
        <Field id={`${kind}-to`} label="Recipient account ID" error={touched ? destinationError : null}>
          <input id={`${kind}-to`} value={toAccountId} onChange={edit(setToAccountId)}
            placeholder="Paste the full account ID" autoComplete="off" spellCheck="false" />
        </Field>
      )}
      <Field id={`${kind}-amount`} label="Amount (₹)" hint="Up to ₹10,00,000.00 per operation" error={touched ? amountError : null}>
        <input id={`${kind}-amount`} inputMode="decimal" value={amount} placeholder="0.00" onChange={edit(setAmount)} autoComplete="off" />
      </Field>
      <Field id={`${kind}-description`} label="Note (optional)" error={touched ? descriptionError : null}>
        <input id={`${kind}-description`} value={description} maxLength={140} onChange={edit(setDescription)} />
      </Field>

      {confirming && !invalid ? (
        <div className="confirm" role="group" aria-label="Confirm">
          <p>
            {label.verb} <strong><Money minor={toMinor(amountText)} /></strong>
            {kind === 'transfer' && <> to account <code>{destination.slice(0, 8)}…</code></>}?
          </p>
          <div className="confirm__actions">
            <button type="button" className="button" onClick={submit} disabled={busy}>
              {busy ? 'Processing…' : `Confirm ${label.verb.toLowerCase()}`}
            </button>
            <button type="button" className="button button--secondary" onClick={() => setConfirming(false)} disabled={busy}>Cancel</button>
          </div>
        </div>
      ) : (
        <button type="submit" className="button" disabled={busy}>{busy ? 'Processing…' : label.verb}</button>
      )}

      <ErrorPanel error={shownError} onRetry={retryable ? submit : undefined} />
      {retryable && <p className="field__hint">Retrying is safe: it resends the same request key, so it cannot be posted twice.</p>}
      <PostingResult result={result} />
    </form>
  );
}
