import react from '@vitejs/plugin-react'
import { readFileSync } from 'node:fs'
import { defineConfig } from 'vite'

// The production security headers live in vercel.json. `vite preview` (used by the E2E tests) serves the same
// headers, with the API placeholder replaced by the API origin of the build under test, so the real
// Content-Security-Policy is exercised locally. HSTS is left out for plain-http localhost.
const API_PLACEHOLDER = 'https://REPLACE-WITH-RENDER-BACKEND-HOST'

function previewHeaders() {
  const vercel = JSON.parse(readFileSync(new URL('./vercel.json', import.meta.url), 'utf8'))
  const apiOrigin = new URL(process.env.VITE_API_BASE_URL ?? 'http://localhost:8080').origin
  const headers = {}
  for (const { key, value } of vercel.headers.find((h) => h.source === '/(.*)').headers) {
    if (key !== 'Strict-Transport-Security') {
      headers[key] = value.replace(API_PLACEHOLDER, apiOrigin)
    }
  }
  return headers
}

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  preview: { headers: previewHeaders() },
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.js'],
    include: ['src/**/*.test.{js,jsx}'],
  },
})
