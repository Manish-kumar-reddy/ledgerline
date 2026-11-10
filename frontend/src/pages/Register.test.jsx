import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router';
import { describe, expect, it, vi } from 'vitest';
import { ApiError } from '../api/errors.js';
import Register from './Register.jsx';
import { validateRegistration } from './registration.js';

describe('Register', () => {
  it('checks the password length in UTF-8 bytes like the backend', () => {
    expect(validateRegistration({ fullName: 'A', email: 'a@b.c', password: 'short' }).password).toMatch(/12/);
    expect(validateRegistration({ fullName: 'A', email: 'a@b.c', password: '😀😀😀' }).password).toBeUndefined();   // 3 characters, but 12 bytes: allowed
    expect(validateRegistration({ fullName: 'A', email: 'a@b.c', password: '😀'.repeat(19) }).password).toMatch(/72/);
    expect(validateRegistration({ fullName: 'A', email: 'a@b.c', password: 'correct horse battery' })).toEqual({});
  });

  it('shows the duplicate-email conflict', async () => {
    const api = { register: vi.fn().mockRejectedValue(new ApiError({ status: 409, title: 'Email already registered' })) };
    render(
      <MemoryRouter initialEntries={['/register']}>
        <Routes><Route path="/register" element={<Register api={api} />} /></Routes>
      </MemoryRouter>,
    );
    const user = userEvent.setup();
    await user.type(screen.getByLabelText('Full name'), 'Asha Rao');
    await user.type(screen.getByLabelText('Email'), 'asha@example.com');
    await user.type(screen.getByLabelText('Password'), 'correct horse battery');
    await user.click(screen.getByRole('button', { name: 'Create account' }));
    expect(await screen.findByText('An account with this email already exists.')).toBeInTheDocument();
  });
});
