import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useNavigate } from 'react-router';
import * as authApi from '../api/auth.js';
import { setAccessToken, setUnauthorizedHandler } from '../api/client.js';
import { AuthContext } from './session.js';

// Session state lives only in memory: a page reload signs the user out (by design; see README).

export function AuthProvider({ children, api = authApi }) {
  const [user, setUser] = useState(null);
  const expiryTimer = useRef(null);
  const navigate = useNavigate();

  const clear = useCallback(() => {
    clearTimeout(expiryTimer.current);
    setAccessToken(null);
    setUser(null);
  }, []);

  const logout = useCallback((reason) => {
    clear();
    navigate(reason ? `/login?reason=${reason}` : '/login', { replace: true });
  }, [clear, navigate]);

  useEffect(() => {
    // Any 401 on a protected request: the token expired, is invalid, or the user was disabled.
    setUnauthorizedHandler(() => logout('expired'));
    return () => setUnauthorizedHandler(() => {});
  }, [logout]);

  useEffect(() => () => clearTimeout(expiryTimer.current), []);

  const login = useCallback(async (email, password) => {
    const token = await api.login({ email, password });
    setAccessToken(token.accessToken);
    try {
      const me = await api.currentUser();
      setUser(me);
      clearTimeout(expiryTimer.current);
      expiryTimer.current = setTimeout(() => logout('expired'), Math.max(0, token.expiresIn - 5) * 1000);
      return me;
    } catch (error) {
      setAccessToken(null);
      throw error;
    }
  }, [api, logout]);

  const value = useMemo(() => ({ user, login, logout }), [user, login, logout]);
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}
