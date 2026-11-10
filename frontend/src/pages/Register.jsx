import { useState } from 'react';
import { Link, useNavigate } from 'react-router';
import * as authApi from '../api/auth.js';
import { ErrorPanel, Field } from '../components/ui.jsx';
import AuthLayout from './AuthLayout.jsx';
import { validateRegistration } from './registration.js';

export default function Register({ api = authApi }) {
  const navigate = useNavigate();
  const [form, setForm] = useState({ fullName: '', email: '', password: '' });
  const [touched, setTouched] = useState(false);
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);
  const errors = validateRegistration(form);
  const serverFieldError = (field) => error?.fieldErrors?.find((e) => e.field === field)?.message;

  async function submit(event) {
    event.preventDefault();
    setTouched(true);
    if (Object.keys(errors).length) return;
    setBusy(true);
    setError(null);
    try {
      await api.register({ fullName: form.fullName.trim(), email: form.email.trim(), password: form.password });
      navigate('/login?registered=1', { replace: true });
    } catch (failure) {
      setError(failure.status === 409 ? { ...failure, title: 'An account with this email already exists.' } : failure);
    } finally {
      setBusy(false);
    }
  }

  const set = (field) => (e) => setForm({ ...form, [field]: e.target.value });
  return (
    <AuthLayout title="Create your account" subtitle="New registrations are account holders.">
      <form onSubmit={submit} className="stack" noValidate>
        <Field id="fullName" label="Full name" error={(touched && errors.fullName) || serverFieldError('fullName')}>
          <input id="fullName" autoComplete="name" value={form.fullName} onChange={set('fullName')} />
        </Field>
        <Field id="email" label="Email" error={(touched && errors.email) || serverFieldError('email')}>
          <input id="email" type="email" autoComplete="email" value={form.email} onChange={set('email')} />
        </Field>
        <Field id="password" label="Password" hint="At least 12 characters. A passphrase works well."
          error={(touched && errors.password) || serverFieldError('password')}>
          <input id="password" type="password" autoComplete="new-password" value={form.password} onChange={set('password')} />
        </Field>
        <ErrorPanel error={error && !error.fieldErrors?.length ? error : null} />
        <button type="submit" className="button button--block" disabled={busy}>{busy ? 'Creating…' : 'Create account'}</button>
      </form>
      <p className="auth__switch">Already registered? <Link to="/login">Sign in</Link></p>
    </AuthLayout>
  );
}
