import { api } from './client.js';

// Read-only views for ADMIN and AUDITOR. The backend enforces the roles; the UI only hides what cannot work.

export async function listAllAccounts(page = 0, size = 25) {
  return (await api.get('/api/oversight/accounts', { params: { page, size } })).data;
}

export async function anyStatement(accountId, page = 0, size = 25) {
  return (await api.get(`/api/oversight/accounts/${encodeURIComponent(accountId)}/statement`, { params: { page, size } })).data;
}

export async function reconciliation() {
  return (await api.get('/api/oversight/reconciliation')).data;
}
