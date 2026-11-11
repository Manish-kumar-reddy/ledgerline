import { useEffect, useState } from 'react';
import { Link, Navigate, useNavigate, useSearchParams } from 'react-router';
import { homePathFor, useAuth } from '../auth/session.js';
import { ErrorPanel, Field } from '../components/ui.jsx';
import SignInShowcase, { BrandMark } from './SignInShowcase.jsx';
import '../styles/signin.css';

/** After this long without an answer, explain that the server may be starting (free hosting sleeps when idle). */
export const SLOW_SIGN_IN_MS = 5000;

const WAKING_HINT = 'If LedgerLine has not been used for a while, the server can take up to a minute to start. '
  + 'Please wait, or try again shortly.';

export default function Login({ slowAfterMs = SLOW_SIGN_IN_MS }) {
  const { user, login } = useAuth();
  const navigate = useNavigate();
  const [params] = useSearchParams();
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);
  const [slow, setSlow] = useState(false);

  useEffect(() => {
    if (!busy) {
      return undefined;
    }
    const timer = setTimeout(() => setSlow(true), slowAfterMs);
    return () => clearTimeout(timer);
  }, [busy, slowAfterMs]);

  if (user) {
    return <Navigate to={homePathFor(user)} replace />;
  }

  async function submit(event) {
    event.preventDefault();
    setBusy(true);
    setSlow(false);
    setError(null);
    try {
      const me = await login(email.trim(), password);
      navigate(homePathFor(me), { replace: true });
    } catch (failure) {
      setError(failure.status === 401 ? { title: 'Invalid email or password.' } : failure);
      setPassword('');
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="signin">
      <SignInShowcase />
      <main className="signin-panel">
        {/* Small screens: the form comes first, under a compact brand header (the full showcase follows below). */}
        <div className="signin-mobile-brand" aria-hidden="true">
          <span className="signin-brand"><BrandMark className="signin-brand__mark" /> LedgerLine</span>
          <span className="signin-mobile-brand__tagline">Every rupee. Every entry. <em>Accounted for.</em></span>
        </div>
        <div className="signin-card">
          <h1 className="signin-card__title">Sign in</h1>
          <p className="signin-card__subtitle">Use the email and password you registered with.</p>
      {params.get('reason') === 'expired' && (
        <div className="panel panel--info" role="status">Your session has ended. Please sign in again.</div>
      )}
      {params.get('registered') === '1' && (
        <div className="panel panel--success" role="status">Registration complete. You can sign in now.</div>
      )}
      <form onSubmit={submit} className="stack signin-form" noValidate>
        <Field id="email" label="Email">
          <input id="email" type="email" autoComplete="username" value={email} onChange={(e) => setEmail(e.target.value)} required />
        </Field>
        <Field id="password" label="Password">
          <input id="password" type="password" autoComplete="current-password" value={password}
            onChange={(e) => setPassword(e.target.value)} required />
        </Field>
        {busy && slow && <p className="panel panel--info" role="status">Still signing in… {WAKING_HINT}</p>}
        <ErrorPanel error={error?.network ? { ...error, detail: WAKING_HINT } : error} />
        <button type="submit" className="button button--block signin-submit" disabled={busy || !email || !password}>
          {busy && <span className="signin-submit__spinner" aria-hidden="true" />}
          {busy ? 'Signing in…' : 'Sign in'}
        </button>
      </form>
          <p className="signin-card__switch">New to LedgerLine? <Link to="/register">Create an account</Link></p>
        </div>
        <p className="signin-panel__note">Simulated accounts in INR · not connected to any real bank</p>
      </main>
    </div>
  );
}
