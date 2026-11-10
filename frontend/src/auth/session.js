import { createContext, useContext } from 'react';

export const AuthContext = createContext(null);

export const ROLE_LABELS = { ACCOUNT_HOLDER: 'Account holder', ADMIN: 'Administrator', AUDITOR: 'Auditor' };

export function useAuth() {
  const context = useContext(AuthContext);
  if (!context) {
    throw new Error('useAuth must be used inside AuthProvider');
  }
  return context;
}

/** Where a signed-in user starts. Purely navigation: the backend decides what each role may do. */
export function homePathFor(user) {
  if (!user) return '/login';
  return user.role === 'ACCOUNT_HOLDER' ? '/accounts' : '/oversight/accounts';
}
