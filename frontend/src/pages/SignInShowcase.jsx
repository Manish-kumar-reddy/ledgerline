import { formatInr } from '../money/money.js';

// The brand side of the sign-in page. The ledger is an ILLUSTRATION of how LedgerLine records money (the worked
// example from the README): it is static sample content, not live data.
const EXAMPLE = [
  { id: 1, entry: 'Deposit', debit: 'Settlement cash', credit: 'Account A', amount: 50_000 },
  { id: 2, entry: 'Withdrawal', debit: 'Account A', credit: 'Settlement cash', amount: 20_000 },
  { id: 3, entry: 'Transfer', debit: 'Account A', credit: 'Account B', amount: 10_000 },
];
const TOTAL = EXAMPLE.reduce((sum, row) => sum + row.amount, 0);

export function BrandMark({ className = '' }) {
  // Two equal bars in a ledger frame: debit = credit.
  return (
    <svg className={className} viewBox="0 0 40 40" aria-hidden="true" focusable="false">
      <rect x="1.5" y="1.5" width="37" height="37" rx="10" fill="none" stroke="currentColor" strokeWidth="2.5" />
      <path d="M20 9v22" stroke="currentColor" strokeWidth="2" strokeLinecap="round" opacity="0.45" />
      <path d="M10 16h7M23 16h7M10 24h7M23 24h7" stroke="currentColor" strokeWidth="3" strokeLinecap="round" />
    </svg>
  );
}

function LedgerIllustration() {
  return (
    <figure className="signin-ledger" aria-label="Example journal: a deposit, a withdrawal and a transfer, each with an equal debit and credit, so total debits equal total credits.">
      <div aria-hidden="true">
        <div className="signin-ledger__bar">
          <span className="signin-ledger__dots"><i /><i /><i /></span>
          <span className="signin-ledger__title">Journal · example</span>
          <span className="signin-ledger__chip">Double entry</span>
        </div>
        <div className="signin-ledger__head">
          <span>Entry</span><span>Debit</span><span>Credit</span>
        </div>
        {EXAMPLE.map((row) => (
          <div key={row.id} className="signin-ledger__row">
            <span className="signin-ledger__entry">
              <span className="signin-ledger__seq">{String(row.id).padStart(2, '0')}</span>{row.entry}
            </span>
            <span className="signin-ledger__side signin-ledger__side--debit">
              <span className="signin-ledger__account">{row.debit}</span>
              <span className="signin-ledger__amount">{formatInr(row.amount)}</span>
            </span>
            <span className="signin-ledger__side signin-ledger__side--credit">
              <span className="signin-ledger__account">{row.credit}</span>
              <span className="signin-ledger__amount">{formatInr(row.amount)}</span>
            </span>
          </div>
        ))}
        <div className="signin-ledger__balance">
          <span className="signin-ledger__total">
            <span className="signin-ledger__total-label">Debits</span>
            <span className="signin-ledger__amount">{formatInr(TOTAL)}</span>
          </span>
          <span className="signin-ledger__equals">=</span>
          <span className="signin-ledger__total">
            <span className="signin-ledger__total-label">Credits</span>
            <span className="signin-ledger__amount">{formatInr(TOTAL)}</span>
          </span>
          <span className="signin-ledger__check">Balanced</span>
        </div>
      </div>
      <figcaption className="signin-ledger__caption">Illustration of how every posting is recorded. Sample figures.</figcaption>
    </figure>
  );
}

export default function SignInShowcase() {
  return (
    <section className="signin-showcase" aria-labelledby="signin-tagline">
      <div className="signin-showcase__grid" aria-hidden="true" />
      <div className="signin-showcase__glow" aria-hidden="true" />
      <div className="signin-showcase__inner">
        <span className="signin-brand"><BrandMark className="signin-brand__mark" /> LedgerLine</span>
        <p id="signin-tagline" className="signin-showcase__headline">
          Every rupee.<br />Every entry.<br /><span className="signin-showcase__accent">Accounted for.</span>
        </p>
        <p className="signin-showcase__lede">
          Money movement on a double-entry ledger: each posting is one debit and one equal credit, and every refused
          attempt stays on the statement.
        </p>
        <LedgerIllustration />
        <ul className="signin-showcase__facts">
          <li><strong>Balanced by design</strong><span>Debits always equal credits.</span></li>
          <li><strong>Nothing hidden</strong><span>Refusals appear with their reason.</span></li>
          <li><strong>Simulated INR</strong><span>Demonstration accounts only.</span></li>
        </ul>
      </div>
    </section>
  );
}
