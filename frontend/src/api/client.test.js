import { afterEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from './errors.js';
import { createClient, setAccessToken, setUnauthorizedHandler } from './client.js';

/** An axios adapter that records requests and answers with the given status. */
function adapter(status, data = {}, headers = {}) {
  const seen = [];
  const fn = async (config) => {
    seen.push(config);
    const response = { status, data, headers, config, statusText: '' };
    if (status >= 200 && status < 300) return response;
    const error = new Error(`status ${status}`);
    error.response = response;
    error.config = config;
    throw error;
  };
  fn.seen = seen;
  return fn;
}

afterEach(() => {
  setAccessToken(null);
  setUnauthorizedHandler(() => {});
});

describe('api client', () => {
  it('sends the in-memory token as a Bearer header', async () => {
    const a = adapter(200, { ok: true });
    setAccessToken('abc');
    await createClient({ adapter: a }).get('/api/auth/me');
    expect(a.seen[0].headers.Authorization).toBe('Bearer abc');
  });

  it('sends no Authorization header without a token', async () => {
    const a = adapter(200);
    await createClient({ adapter: a }).get('/api/health');
    expect(a.seen[0].headers.Authorization).toBeUndefined();
  });

  it('a 401 on a protected request ends the session', async () => {
    const onUnauthorized = vi.fn();
    setUnauthorizedHandler(onUnauthorized);
    await expect(createClient({ adapter: adapter(401) }).get('/api/accounts')).rejects.toBeInstanceOf(ApiError);
    expect(onUnauthorized).toHaveBeenCalledOnce();
  });

  it('a 401 from login is a wrong password, not an expired session', async () => {
    const onUnauthorized = vi.fn();
    setUnauthorizedHandler(onUnauthorized);
    await expect(createClient({ adapter: adapter(401, { title: 'Invalid email or password' }) })
      .post('/api/auth/login', {})).rejects.toMatchObject({ status: 401 });
    expect(onUnauthorized).not.toHaveBeenCalled();
  });

  it('a 403 is reported, not treated as signed out', async () => {
    const onUnauthorized = vi.fn();
    setUnauthorizedHandler(onUnauthorized);
    await expect(createClient({ adapter: adapter(403, { title: 'Forbidden' }) }).get('/api/oversight/reconciliation'))
      .rejects.toMatchObject({ status: 403, title: 'Forbidden' });
    expect(onUnauthorized).not.toHaveBeenCalled();
  });
});
