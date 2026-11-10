import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router';
import { describe, expect, it, vi } from 'vitest';
import { ApiError } from '../api/errors.js';
import { AuthContext } from '../auth/session.js';
import Login from './Login.jsx';

function renderLogin(login, path = '/login') {
  render(
    <MemoryRouter initialEntries={[path]}>
      <AuthContext.Provider value={{ user: null, login, logout: vi.fn() }}>
        <Routes>
          <Route path="/login" element={<Login />} />
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
});
