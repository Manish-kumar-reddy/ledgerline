import react from '@vitejs/plugin-react'
import { readFileSync } from 'node:fs'
import { defineConfig } from 'vite'

// The production security headers live in vercel.json. `vite preview` (used by the E2E tests) serves the same
// headers, with the production API origin in `connect-src 'self' <origin>` replaced by the API origin of the build
// under test (http://localhost:8081 in run-e2e.sh), so the real Content-Security-Policy is exercised locally.
// HSTS is left out for plain-http localhost.
const CONNECT_SRC_API_ORIGIN = /(connect-src 'self') [^;\s]+/

function previewHeaders() {
  const vercel = JSON.parse(readFileSync(new URL('./vercel.json', import.meta.url), 'utf8'))
  const apiOrigin = new URL(process.env.VITE_API_BASE_URL ?? 'http://localhost:8080').origin
  const headers = {}
  for (const { key, value } of vercel.headers.find((h) => h.source === '/(.*)').headers) {
    if (key === 'Strict-Transport-Security') continue
    if (key === 'Content-Security-Policy') {
      if (!CONNECT_SRC_API_ORIGIN.test(value)) {
        throw new Error("vercel.json Content-Security-Policy has no \"connect-src 'self' <API origin>\" to point at "
          + `${apiOrigin} for vite preview; refusing to serve a policy that would block the API under test.`)
      }
      headers[key] = value.replace(CONNECT_SRC_API_ORIGIN, `$1 ${apiOrigin}`)
    } else {
      headers[key] = value
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
