import { useState } from 'react';
import { formatInr, shortId } from '../money/money.js';

export function Money({ minor, className = '' }) {
  return <span className={`money ${Number(minor) < 0 ? 'money--negative' : ''} ${className}`}>{formatInr(minor)}</span>;
}

const STATUS_TONES = { ACTIVE: 'positive', FROZEN: 'warning', CLOSED: 'muted', POSTED: 'positive', REFUSED: 'danger', SYSTEM: 'info' };

export function StatusBadge({ value, label }) {
  return <span className={`badge badge--${STATUS_TONES[value] ?? 'muted'}`}>{label ?? value}</span>;
}

export function CopyId({ id, label = 'Account ID' }) {
  const [copied, setCopied] = useState(false);
  async function copy() {
    try {
      await navigator.clipboard.writeText(id);
      setCopied(true);
      setTimeout(() => setCopied(false), 1500);
    } catch {
      setCopied(false);
    }
  }
  return (
    <span className="copy-id">
      <span className="copy-id__label">{label}</span>
      <code title={id} data-testid="full-account-id" data-id={id}>{shortId(id)}…</code>
      <button type="button" className="link-button" onClick={copy} aria-label={`Copy ${label.toLowerCase()} ${id}`}>
        {copied ? 'Copied' : 'Copy'}
      </button>
    </span>
  );
}

export function ErrorPanel({ error, onRetry }) {
  if (!error) return null;
  return (
    <div className="panel panel--error" role="alert">
      <strong>{error.title}</strong>
      {error.detail && <p>{error.detail}</p>}
      {error.retryAfterSeconds != null && <p>Try again in {error.retryAfterSeconds} s.</p>}
      {onRetry && <button type="button" className="button button--secondary" onClick={onRetry}>Try again</button>}
    </div>
  );
}

export function EmptyState({ title, children }) {
  return (
    <div className="empty">
      <p className="empty__title">{title}</p>
      {children}
    </div>
  );
}

export function Skeleton({ rows = 3 }) {
  return (
    <div className="skeleton" aria-busy="true" aria-label="Loading">
      {Array.from({ length: rows }, (_, i) => <div key={i} className="skeleton__row" />)}
    </div>
  );
}

export function Pagination({ page, totalPages, onChange }) {
  if (!totalPages || totalPages <= 1) return null;
  return (
    <nav className="pagination" aria-label="Pages">
      <button type="button" className="button button--secondary" disabled={page <= 0} onClick={() => onChange(page - 1)}>Previous</button>
      <span>Page {page + 1} of {totalPages}</span>
      <button type="button" className="button button--secondary" disabled={page + 1 >= totalPages} onClick={() => onChange(page + 1)}>Next</button>
    </nav>
  );
}

export function Field({ id, label, hint, error, children }) {
  return (
    <div className={`field ${error ? 'field--error' : ''}`}>
      <label htmlFor={id}>{label}</label>
      {children}
      {hint && !error && <p className="field__hint" id={`${id}-hint`}>{hint}</p>}
      {error && <p className="field__error" id={`${id}-error`} role="alert">{error}</p>}
    </div>
  );
}
