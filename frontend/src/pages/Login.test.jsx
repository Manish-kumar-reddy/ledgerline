import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router';
import { describe, expect, it, vi } from 'vitest';
import { ApiError } from '../api/errors.js';
import { AuthContext } from '../auth/session.js';
import Login from './Login.jsx';

function renderLogin(login, path = '/login', props = {}) {
  render(
    <MemoryRouter initialEntries={[path]}>
      <AuthContext.Provider value={{ user: null, login, logout: vi.fn() }}>
        <Routes>
          <Route path="/login" element={<Login {...props} />} />
          <Route path="/accounts" element={<p>accounts home</p>} />
        </Routes>
      </AuthContext.Provider>
    </MemoryRouter>,
  );
  return userEvent.setup();
}

async function signIn(user) {
  await user.type(screen.getByLabelText('Email'), 'asha@example.com');
  await user.type(screen.getByLabelText('Password'), 'correct horse battery staple');
  await user.click(screen.getByRole('button', { name: 'Sign in' }));
}

describe('Login', () => {
  it('signs in and goes to the role home page', async () => {
    const login = vi.fn().mockResolvedValue({ role: 'ACCOUNT_HOLDER' });
    await signIn(renderLogin(login));
    expect(login).toHaveBeenCalledWith('asha@example.com', 'correct horse battery staple');
    expect(await screen.findByText('accounts home')).toBeInTheDocument();
  });

  it('shows one generic message for a 401 and clears the password', async () => {
    const login = vi.fn().mockRejectedValue(new ApiError({ status: 401, title: 'Invalid email or password' }));
    await signIn(renderLogin(login));
    expect(await screen.findByText('Invalid email or password.')).toBeInTheDocument();
    expect(screen.getByLabelText('Password')).toHaveValue('');
  });

  it('shows the wait time for a 429', async () => {
    const login = vi.fn().mockRejectedValue(new ApiError({ status: 429, title: 'Too many login attempts', retryAfterSeconds: 840 }));
    await signIn(renderLogin(login));
    expect(await screen.findByText('Too many login attempts')).toBeInTheDocument();
    expect(screen.getByText('Try again in 840 s.')).toBeInTheDocument();
  });

  it('explains an expired session', () => {
    renderLogin(vi.fn(), '/login?reason=expired');
    expect(screen.getByText(/session has ended/)).toBeInTheDocument();
  });

  it('explains a possible cold start when signing in is slow, without resubmitting', async () => {
    let finish;
    const login = vi.fn().mockReturnValue(new Promise((resolve) => { finish = resolve; }));
    const user = renderLogin(login, '/login', { slowAfterMs: 20 });
    await signIn(user);
    expect(await screen.findByText(/Still signing in/)).toHaveTextContent('up to a minute to start');
    expect(login).toHaveBeenCalledTimes(1);
    finish({ role: 'ACCOUNT_HOLDER' });
    expect(await screen.findByText('accounts home')).toBeInTheDocument();
  });

  it('adds the start-up hint when the server cannot be reached', async () => {
    const login = vi.fn().mockRejectedValue(new ApiError({ status: 0, network: true, title: 'Cannot reach the LedgerLine server.' }));
    await signIn(renderLogin(login));
    expect(await screen.findByText('Cannot reach the LedgerLine server.')).toBeInTheDocument();
    expect(screen.getByText(/up to a minute to start/)).toBeInTheDocument();
    expect(screen.queryByText(/Still signing in/)).not.toBeInTheDocument();
  });

  it('does not show the start-up hint for a quick wrong password', async () => {
    const login = vi.fn().mockRejectedValue(new ApiError({ status: 401, title: 'Invalid email or password' }));
    await signIn(renderLogin(login));
    await screen.findByText('Invalid email or password.');
    expect(screen.queryByText(/up to a minute/)).not.toBeInTheDocument();
  });
});
