// Money at the UI boundary. Amounts are typed and sent as strings ("1500.50") and displayed from integer paise.
// Floating point is never used: the backend converts the string exactly, and display divides integers.

/** Same rule as the backend (Money.AMOUNT_REGEX): positive, up to 9 rupee digits, at most 2 decimals. */
export const AMOUNT_PATTERN = /^(?!0+(\.0{1,2})?$)(0|[1-9][0-9]{0,8})(\.[0-9]{1,2})?$/;

/** Backend limits (enforced by PostgreSQL); shown here only as guidance. */
export const MAX_OPERATION_MINOR = 100_000_000;     // Rs 10,00,000.00
export const MAX_BALANCE_MINOR = 1_000_000_000;     // Rs 1,00,00,000.00

export function isValidAmount(text) {
  return typeof text === 'string' && AMOUNT_PATTERN.test(text);
}

/** "1500.5" -> 150050. Integer arithmetic only (values stay far below 2^53). */
export function toMinor(text) {
  if (!isValidAmount(text)) {
    throw new Error('Not a valid amount');
  }
  const [rupees, paise = ''] = text.split('.');
  return Number(rupees) * 100 + Number(paise.padEnd(2, '0'));
}

const grouping = new Intl.NumberFormat('en-IN', { maximumFractionDigits: 0 });

/** 1000000000 -> "₹1,00,00,000.00"; -30000 -> "−₹300.00". */
export function formatInr(minor) {
  const value = Number(minor);
  if (!Number.isSafeInteger(value)) {
    return '—';
  }
  const negative = value < 0;
  const abs = Math.abs(value);
  const rupees = Math.trunc(abs / 100);
  const paise = String(abs % 100).padStart(2, '0');
  return `${negative ? '−' : ''}₹${grouping.format(rupees)}.${paise}`;
}

/** First 8 characters of a UUID, for display next to a copy button. */
export function shortId(id) {
  return typeof id === 'string' ? id.slice(0, 8) : '';
}
