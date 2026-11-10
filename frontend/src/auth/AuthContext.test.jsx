import { act, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router';
import { describe, expect, it, vi } from 'vitest';
import { createClient } from '../api/client.js';
import { AuthProvider } from './AuthContext.jsx';
import { useAuth } from './session.js';

function Where() {
  const location = useLocation();
  return <p data-testid="where">{location.pathname + location.search}</p>;
}

function Probe() {
  const { user, login } = useAuth();
  return (
    <>
      <p data-testid="user">{user ? user.fullName : 'nobody'}</p>
      <button type="button" onClick={() => login('a@b.c', 'pw')}>login</button>
    </>
  );
}

describe('AuthProvider', () => {
  it('keeps the token in memory only and ends the session on a 401 from a protected call', async () => {
    const api = {
      login: vi.fn().mockResolvedValue({ accessToken: 'token-1', tokenType: 'Bearer', expiresIn: 900 }),
      currentUser: vi.fn().mockResolvedValue({ id: 'u1', fullName: 'Asha Rao', role: 'ACCOUNT_HOLDER' }),
    };
    render(
      <MemoryRouter initialEntries={['/home']}>
        <AuthProvider api={api}>
          <Routes><Route path="*" element={<><Probe /><Where /></>} /></Routes>
        </AuthProvider>
      </MemoryRouter>,
    );
    await userEvent.setup().click(screen.getByRole('button', { name: 'login' }));
    expect(await screen.findByText('Asha Rao')).toBeInTheDocument();
    expect(window.localStorage.length).toBe(0);
    expect(window.sessionStorage.length).toBe(0);

    // A protected request that comes back 401 (expired or invalid token).
    const seen = [];
    const client = createClient({ adapter: async (config) => {
      seen.push(config.headers.Authorization);
      const error = new Error('401');
      error.response = { status: 401, data: {}, headers: {}, config };
      error.config = config;
      throw error;
    } });
    await act(async () => { await client.get('/api/accounts').catch(() => {}); });
    expect(seen[0]).toBe('Bearer token-1');
    expect(screen.getByTestId('user')).toHaveTextContent('nobody');
    expect(screen.getByTestId('where')).toHaveTextContent('/login?reason=expired');
  });
});
