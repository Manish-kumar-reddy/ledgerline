import axios from 'axios';
import { toApiError } from './errors.js';

// The only configuration is the backend's public URL. There are no secrets in the frontend.
export const API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8080';

let accessToken = null;
let onUnauthorized = () => {};

/** The token lives only in memory (never localStorage, cookies or the URL). */
export function setAccessToken(token) {
  accessToken = token;
}

/** Called when a protected request returns 401: the token is expired, invalid, or the user was disabled. */
export function setUnauthorizedHandler(handler) {
  onUnauthorized = handler;
}

const PUBLIC_PATHS = ['/api/auth/login', '/api/auth/register'];

export function createClient(options = {}) {
  const client = axios.create({ baseURL: API_BASE_URL, timeout: 15_000, ...options });

  client.interceptors.request.use((config) => {
    if (accessToken) {
      config.headers.Authorization = `Bearer ${accessToken}`;
    }
    return config;
  });

  client.interceptors.response.use(
    (response) => response,
    (error) => {
      const status = error?.response?.status;
      const path = error?.config?.url ?? '';
      if (status === 401 && !PUBLIC_PATHS.some((p) => path.startsWith(p))) {
        onUnauthorized();
      }
      return Promise.reject(toApiError(error));
    },
  );
  return client;
}

export const api = createClient();
