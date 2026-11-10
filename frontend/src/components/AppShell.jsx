import { NavLink, Outlet } from 'react-router';
import { ROLE_LABELS, useAuth } from '../auth/session.js';
import { navigationFor } from './navigation.js';

export default function AppShell() {
  const { user, logout } = useAuth();
  return (
    <div className="shell">
      <header className="topbar">
        <div className="topbar__inner">
          <span className="brand"><span className="brand__mark" aria-hidden="true">L</span> LedgerLine</span>
          <nav className="topbar__nav" aria-label="Main">
            {navigationFor(user?.role).map((item) => (
              <NavLink key={item.to} to={item.to} className="topbar__link">{item.label}</NavLink>
            ))}
          </nav>
          <div className="topbar__user">
            <span className="topbar__name">{user?.fullName}</span>
            <span className={`role role--${user?.role?.toLowerCase()}`}>{ROLE_LABELS[user?.role] ?? user?.role}</span>
            <button type="button" className="button button--ghost" onClick={() => logout()}>Sign out</button>
          </div>
        </div>
      </header>
      {user?.role !== 'ACCOUNT_HOLDER' && (
        <div className="readonly-banner" role="note">Read-only oversight. Money can only be moved by account holders.</div>
      )}
      <main className="content"><Outlet /></main>
      <footer className="footer">LedgerLine · simulated accounts in INR · not connected to any real bank</footer>
    </div>
  );
}
