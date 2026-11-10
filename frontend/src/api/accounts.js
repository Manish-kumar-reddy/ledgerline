import { api } from './client.js';

export async function listAccounts(page = 0, size = 20) {
  return (await api.get('/api/accounts', { params: { page, size } })).data;
}

export async function getAccount(id) {
  return (await api.get(`/api/accounts/${encodeURIComponent(id)}`)).data;
}

export async function createAccount(name) {
  return (await api.post('/api/accounts', { name })).data;
}
