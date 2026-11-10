/** Navigation shows only what the signed-in role can use. The backend still authorizes every request. */
export function navigationFor(role) {
  if (role === 'ACCOUNT_HOLDER') {
    return [{ to: '/accounts', label: 'My accounts' }];
  }
  if (role === 'ADMIN' || role === 'AUDITOR') {
    return [{ to: '/oversight/accounts', label: 'All accounts' }, { to: '/oversight/reconciliation', label: 'Reconciliation' }];
  }
  return [];
}
