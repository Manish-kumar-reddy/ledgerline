export default function AuthLayout({ title, subtitle, children }) {
  return (
    <div className="auth">
      <aside className="auth__brand">
        <span className="brand brand--large"><span className="brand__mark" aria-hidden="true">L</span> LedgerLine</span>
        <p className="auth__tagline">Money movement with a ledger you can prove.</p>
        <ul className="auth__points">
          <li>Every rupee recorded as a balanced double entry</li>
          <li>Every refused attempt kept on your statement</li>
          <li>Simulated accounts in INR, for demonstration only</li>
        </ul>
      </aside>
      <main className="auth__panel">
        <h1>{title}</h1>
        {subtitle && <p className="muted">{subtitle}</p>}
        {children}
      </main>
    </div>
  );
}
