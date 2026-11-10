import { useState } from 'react';
import { Link, Navigate, useNavigate, useSearchParams } from 'react-router';
import { homePathFor, useAuth } from '../auth/session.js';
import { ErrorPanel, Field } from '../components/ui.jsx';
import AuthLayout from './AuthLayout.jsx';

export default function Login() {
  const { user, login } = useAuth();
  const navigate = useNavigate();
  const [params] = useSearchParams();
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);

  if (user) {
    return <Navigate to={homePathFor(user)} replace />;
  }

  async function submit(event) {
    event.preventDefault();
    setBusy(true);
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
    <AuthLayout title="Sign in" subtitle="Use the email and password you registered with.">
      {params.get('reason') === 'expired' && (
        <div className="panel panel--info" role="status">Your session has ended. Please sign in again.</div>
      )}
      {params.get('registered') === '1' && (
        <div className="panel panel--success" role="status">Registration complete. You can sign in now.</div>
      )}
      <form onSubmit={submit} className="stack" noValidate>
        <Field id="email" label="Email">
          <input id="email" type="email" autoComplete="username" value={email} onChange={(e) => setEmail(e.target.value)} required />
        </Field>
        <Field id="password" label="Password">
          <input id="password" type="password" autoComplete="current-password" value={password}
            onChange={(e) => setPassword(e.target.value)} required />
        </Field>
        <ErrorPanel error={error} />
        <button type="submit" className="button button--block" disabled={busy || !email || !password}>
          {busy ? 'Signing in…' : 'Sign in'}
        </button>
      </form>
      <p className="auth__switch">New to LedgerLine? <Link to="/register">Create an account</Link></p>
    </AuthLayout>
  );
}
