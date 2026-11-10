import { api } from './client.js';

export async function register({ fullName, email, password }) {
  return (await api.post('/api/auth/register', { fullName, email, password })).data;
}

export async function login({ email, password }) {
  return (await api.post('/api/auth/login', { email, password })).data;
}

export async function currentUser() {
  return (await api.get('/api/auth/me')).data;
}
