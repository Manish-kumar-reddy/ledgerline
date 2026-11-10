#!/usr/bin/env bash
# End-to-end tests: real browser -> production frontend build -> real backend -> ledgerline_test_db.
# Never touches ledgerline_db. Credentials come from backend/.env into the environment only (never printed, never
# passed as command-line arguments). Nothing is reset or deleted: tests use fresh, unique users.
set -euo pipefail
cd "$(dirname "$0")/.."
BACKEND="$(cd ../backend && pwd)"

set -a
. "$BACKEND/.env"
set +a
case "${TEST_DB_URL:-}" in
  */ledgerline_test_db|*/ledgerline_test_db\?*) ;;
  *) echo "Refusing to run: TEST_DB_URL must point at ledgerline_test_db." >&2; exit 1 ;;
esac
export DB_URL="$TEST_DB_URL"                       # the backend under test uses the TEST database
export CORS_ALLOWED_ORIGINS="http://localhost:4173"
export JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 21)}"

(cd "$BACKEND" && ./mvnw -q -DskipTests package)
JAR="$(ls "$BACKEND"/target/ledgerline-backend-*.jar | grep -v plain | head -1)"

# Started from this directory (no .env here), so only the variables exported above are used.
"$JAVA_HOME/bin/java" -jar "$JAR" --server.port=8081 \
  --ledgerline.registration-rate-limit.max-attempts-per-ip=1000 > e2e/backend.log 2>&1 &
BACKEND_PID=$!
trap 'kill "$BACKEND_PID" 2>/dev/null || true' EXIT

for _ in $(seq 1 90); do
  if curl -sf http://localhost:8081/api/health > /dev/null; then break; fi
  if ! kill -0 "$BACKEND_PID" 2>/dev/null; then echo "Backend failed to start; see e2e/backend.log" >&2; exit 1; fi
  sleep 1
done
curl -sf http://localhost:8081/api/health > /dev/null

VITE_API_BASE_URL="http://localhost:8081" npm run build
npx playwright test "$@"
