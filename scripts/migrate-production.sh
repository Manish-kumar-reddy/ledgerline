#!/usr/bin/env bash
# Runs the Flyway migrations (V1..Vn) against the dedicated PRODUCTION database, from the developer's machine,
# using the backend jar's existing migration mode (no web server; MigrationRoleValidator and V2 still apply).
#
#   scripts/migrate-production.sh ~/.ledgerline/production-migrate.env
#
# The environment file must live OUTSIDE the repository, be readable only by you (chmod 600) and contain:
#   DB_URL=jdbc:postgresql://<neon-direct-host>/ledgerline?sslmode=require
#   DB_USERNAME=ledgerline_app            (used only as the ${app_role} placeholder)
#   DB_PASSWORD=<ledgerline_app password>
#   DB_OWNER_USERNAME=ledgerline_owner
#   DB_OWNER_PASSWORD=<ledgerline_owner password>
#   JWT_SECRET=<the production JWT secret>   (required by the application's start-up checks)
#
# The long-running application (Render) must NEVER receive DB_OWNER_USERNAME or DB_OWNER_PASSWORD; it runs with
# SPRING_FLYWAY_ENABLED=false and only ledgerline_app credentials. This script never starts the application.
set -euo pipefail

die() { echo "migrate-production: $*" >&2; exit 1; }

REPO="$(cd "$(dirname "$0")/.." && pwd)"
ENV_FILE="${1:-}"
[ -n "$ENV_FILE" ] || die "usage: scripts/migrate-production.sh /path/outside/repo/production-migrate.env"
[ -f "$ENV_FILE" ] || die "environment file not found"
ENV_FILE="$(cd "$(dirname "$ENV_FILE")" && pwd)/$(basename "$ENV_FILE")"
case "$ENV_FILE" in "$REPO"/*) die "the environment file must be outside the repository" ;; esac
PERMS="$(stat -f %Lp "$ENV_FILE" 2>/dev/null || stat -c %a "$ENV_FILE")"
[ "$PERMS" = "600" ] || [ "$PERMS" = "400" ] || die "environment file permissions are $PERMS; run: chmod 600 \"$ENV_FILE\""

# Load into the environment only (values are never printed or passed as arguments).
set -a
. "$ENV_FILE"
set +a
for name in DB_URL DB_USERNAME DB_PASSWORD DB_OWNER_USERNAME DB_OWNER_PASSWORD JWT_SECRET; do
  [ -n "${!name:-}" ] || die "$name is missing from the environment file"
done

# Only the two roles created by create-production-database.sql are acceptable.
[ "$DB_USERNAME" = "ledgerline_app" ] || die "DB_USERNAME must be ledgerline_app (the runtime role); refusing"
[ "$DB_OWNER_USERNAME" = "ledgerline_owner" ] || die "DB_OWNER_USERNAME must be ledgerline_owner (the migration owner); refusing"

# The URL must be a non-local PostgreSQL URL with TLS required.
[[ "$DB_URL" =~ ^jdbc:postgresql://([^/:?]+)(:[0-9]+)?/([A-Za-z0-9_]+)\?(.*)$ ]] || die "DB_URL is not jdbc:postgresql://host[:port]/database?..."
DB_HOST="${BASH_REMATCH[1]}"
DB_NAME="${BASH_REMATCH[3]}"
DB_QUERY="${BASH_REMATCH[4]}"
shopt -s nocasematch
case "$DB_HOST" in
  localhost|127.*|0.0.0.0|::1|host.docker.internal|*.local) die "refusing a local database host ($DB_HOST)" ;;
esac
case "$DB_NAME" in
  ledgerline_db|ledgerline_test_db) die "refusing the local development database names ($DB_NAME)" ;;
esac
[[ "$DB_QUERY" =~ (^|&)sslmode=(require|verify-ca|verify-full)(&|$) ]] || die "DB_URL must include sslmode=require (or verify-ca / verify-full)"
shopt -u nocasematch
# Only "true" (or unset) is accepted; anything else (FALSE, False, 0, no, ...) could disable or confuse migrations.
if [ -n "${SPRING_FLYWAY_ENABLED+set}" ] && [ "$SPRING_FLYWAY_ENABLED" != "true" ]; then
  die "SPRING_FLYWAY_ENABLED must be unset or exactly \"true\"; refusing"
fi

echo "Target database: $DB_NAME on $DB_HOST (TLS required). Migration user: $DB_OWNER_USERNAME. Application role: $DB_USERNAME."
printf 'Type "migrate %s" to apply migrations to this PRODUCTION database: ' "$DB_NAME"
read -r ANSWER < /dev/tty
[ "$ANSWER" = "migrate $DB_NAME" ] || die "not confirmed; nothing was done"

export JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 21 2>/dev/null || true)}"
[ -n "$JAVA_HOME" ] || die "set JAVA_HOME to a Java 21 JDK"
(cd "$REPO/backend" && ./mvnw -q -B -Dmaven.test.skip=true package)
JAR="$REPO/backend/target/ledgerline-backend-0.0.1-SNAPSHOT.jar"

# Run from an empty directory (so no local .env file can be picked up), in migration mode (no web server), with an
# EMPTY environment: `env -i` passes only PATH and HOME, and the clean shell receives exactly the six validated values
# through a pipe (never as command-line arguments), then adds the two fixed Spring settings. Nothing else inherited
# from this shell or the environment file (SPRING_DATASOURCE_URL, SPRING_FLYWAY_URL, SPRING_FLYWAY_USER,
# SPRING_APPLICATION_JSON, SPRING_CONFIG_*, JAVA_TOOL_OPTIONS, ...) can reach the Java process.
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
cd "$WORK"
if declare -p DB_URL DB_USERNAME DB_PASSWORD DB_OWNER_USERNAME DB_OWNER_PASSWORD JWT_SECRET \
   | env -i PATH="$PATH" HOME="$HOME" /bin/bash --noprofile --norc -c '
       set -eu
       eval "$(cat)"
       export DB_URL DB_USERNAME DB_PASSWORD DB_OWNER_USERNAME DB_OWNER_PASSWORD JWT_SECRET
       export SPRING_MAIN_WEB_APPLICATION_TYPE=none SPRING_FLYWAY_ENABLED=true
       exec "$0" -jar "$1" < /dev/null' "$JAVA_HOME/bin/java" "$JAR"; then
  echo "Migrations applied and validated (exit 0)."
else
  status=$?
  die "migration FAILED (exit $status). Do not deploy the new application version."
fi
