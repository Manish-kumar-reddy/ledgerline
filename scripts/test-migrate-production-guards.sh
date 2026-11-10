#!/usr/bin/env bash
# Guard tests for scripts/migrate-production.sh. Uses dummy values only and never connects to any database. Every
# refusal case must stop before the confirmation prompt. The final isolation case runs the script to the end (it
# builds the jar) with a fake `java` that records what it would have received instead of starting.
#
#   scripts/test-migrate-production-guards.sh
set -uo pipefail
REPO="$(cd "$(dirname "$0")/.." && pwd)"
RUNNER="$REPO/scripts/migrate-production.sh"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

GOOD_URL="jdbc:postgresql://ep-example.neon.tech/ledgerline?sslmode=require"
failures=0

write_env() {   # url app_user owner_user [mode]
  printf '%s\n' "DB_URL=$1" "DB_USERNAME=$2" "DB_PASSWORD=dummy-not-a-secret" "DB_OWNER_USERNAME=$3" \
    "DB_OWNER_PASSWORD=dummy-not-a-secret" "JWT_SECRET=dummy-not-a-secret" > "$WORK/env"
  chmod "${4:-600}" "$WORK/env"
}

expect_refused() {   # description expected-message [env-file]
  local output status
  output="$("$RUNNER" "${3:-$WORK/env}" < /dev/null 2>&1)"; status=$?
  if [ "$status" -ne 0 ] && grep -q -- "$2" <<< "$output" && ! grep -q "Type \"migrate" <<< "$output"; then
    echo "ok    $1"
  else
    echo "FAIL  $1 (exit $status): $output"
    failures=$((failures + 1))
  fi
}

write_env "$GOOD_URL" ledgerline_app ledgerline_admin
expect_refused "owner user is not ledgerline_owner" "DB_OWNER_USERNAME must be ledgerline_owner"
write_env "$GOOD_URL" neondb_owner ledgerline_owner
expect_refused "app user is not ledgerline_app" "DB_USERNAME must be ledgerline_app"
write_env "$GOOD_URL" ledgerline_owner ledgerline_owner
expect_refused "app user equal to the owner" "DB_USERNAME must be ledgerline_app"
write_env "jdbc:postgresql://localhost:5432/ledgerline?sslmode=require" ledgerline_app ledgerline_owner
expect_refused "localhost" "refusing a local database host"
write_env "jdbc:postgresql://127.0.0.1/ledgerline?sslmode=require" ledgerline_app ledgerline_owner
expect_refused "loopback address" "refusing a local database host"
write_env "jdbc:postgresql://ep-example.neon.tech/ledgerline_db?sslmode=require" ledgerline_app ledgerline_owner
expect_refused "local database name ledgerline_db" "refusing the local development database names"
write_env "jdbc:postgresql://ep-example.neon.tech/ledgerline_test_db?sslmode=require" ledgerline_app ledgerline_owner
expect_refused "local database name ledgerline_test_db" "refusing the local development database names"
write_env "jdbc:postgresql://ep-example.neon.tech/ledgerline?sslmode=disable" ledgerline_app ledgerline_owner
expect_refused "sslmode=disable" "must include sslmode=require"
write_env "jdbc:postgresql://ep-example.neon.tech/ledgerline" ledgerline_app ledgerline_owner
expect_refused "no sslmode" "DB_URL is not jdbc:postgresql"
write_env "$GOOD_URL" ledgerline_app ledgerline_owner 644
expect_refused "file readable by others" "permissions are 644"
write_env "$GOOD_URL" ledgerline_app ledgerline_owner
cp "$WORK/env" "$REPO/.guard-test.env" && chmod 600 "$REPO/.guard-test.env"
expect_refused "file inside the repository" "must be outside the repository" "$REPO/.guard-test.env"
rm -f "$REPO/.guard-test.env"
expect_refused "missing file" "environment file not found" "$WORK/does-not-exist.env"

# SPRING_FLYWAY_ENABLED: only unset or exactly "true".
for value in false FALSE False 0 no yes TRUE True; do
  write_env "$GOOD_URL" ledgerline_app ledgerline_owner
  printf 'SPRING_FLYWAY_ENABLED=%s\n' "$value" >> "$WORK/env"
  expect_refused "SPRING_FLYWAY_ENABLED=\"$value\"" 'SPRING_FLYWAY_ENABLED must be unset or exactly'
done

# Runs the script in a pseudo-terminal (it reads the confirmation from /dev/tty), types $2 once the prompt appears,
# and prints everything it wrote. Exit status is the script's.
run_with_tty() {   # env-file answer
  python3 - "$RUNNER" "$1" "$2" <<'PY'
import os, pty, select, sys, time
runner, env_file, answer = sys.argv[1:4]
pid, fd = pty.fork()
if pid == 0:
    os.execv(runner, [runner, env_file])
output, sent, deadline = b"", False, time.time() + 900
while time.time() < deadline:
    ready, _, _ = select.select([fd], [], [], 1)
    if fd in ready:
        try:
            data = os.read(fd, 4096)
        except OSError:
            break
        if not data:
            break
        output += data
        if not sent and b"to apply migrations" in output:
            os.write(fd, answer.encode() + b"\n")
            sent = True
_, status = os.waitpid(pid, 0)
sys.stdout.write(output.decode(errors="replace").replace("\r", ""))
sys.exit(os.waitstatus_to_exitcode(status))
PY
}

write_env "$GOOD_URL" ledgerline_app ledgerline_owner
confirm_output="$(run_with_tty "$WORK/env" "migrate wrongname" 2>&1)"; confirm_status=$?
if [ "$confirm_status" -ne 0 ] && grep -q "not confirmed; nothing was done" <<< "$confirm_output"; then
  echo "ok    wrong confirmation typed at the prompt"
else
  echo "FAIL  wrong confirmation (exit $confirm_status): $confirm_output"; failures=$((failures + 1))
fi

# Environment isolation: run the script to the end with the real confirmation, hostile Spring variables inherited
# from the shell AND present in the environment file, and a fake JAVA_HOME whose `java` passes the Maven build
# through to the real JDK but, for the final `java -jar <backend jar>`, only records its environment and arguments
# and exits 0. No database is contacted.
isolation_test() {
  local real_java fake capture out
  real_java="${JAVA_HOME:-$(/usr/libexec/java_home -v 21 2>/dev/null || true)}"
  [ -x "$real_java/bin/java" ] || { echo "FAIL  environment isolation: no Java 21 JDK found"; failures=$((failures + 1)); return; }
  fake="$WORK/fake-jdk"
  capture="$WORK/capture"
  mkdir -p "$fake/bin" "$capture"
  cat > "$fake/bin/java" <<JAVA
#!/bin/sh
if [ "\$1" = "-jar" ] && [ "\${2##*/}" = "ledgerline-backend-0.0.1-SNAPSHOT.jar" ]; then
  env > "$capture/env"
  printf '%s\n' "\$@" > "$capture/args"
  exit 0
fi
exec "$real_java/bin/java" "\$@"
JAVA
  chmod +x "$fake/bin/java"

  write_env "$GOOD_URL" ledgerline_app ledgerline_owner
  printf '%s\n' "SPRING_FLYWAY_URL=jdbc:postgresql://localhost/ledgerline_db" "SPRING_DATASOURCE_URL=jdbc:postgresql://localhost/ledgerline_db" \
    "JAVA_TOOL_OPTIONS=-Dspring.datasource.url=jdbc:postgresql://localhost/ledgerline_db" >> "$WORK/env"

  out="$WORK/isolation.out"
  env \
      JAVA_HOME="$fake" \
      SPRING_DATASOURCE_URL="jdbc:postgresql://localhost/ledgerline_db" \
      SPRING_FLYWAY_URL="jdbc:postgresql://localhost/ledgerline_db" \
      SPRING_FLYWAY_USER="postgres" \
      SPRING_FLYWAY_PASSWORD="hostile" \
      SPRING_APPLICATION_JSON='{"spring":{"datasource":{"url":"jdbc:postgresql://localhost/ledgerline_db"}}}' \
      SPRING_CONFIG_LOCATION="file:/tmp/hostile/" \
      SPRING_CONFIG_IMPORT="file:/tmp/hostile.properties" \
      SPRING_PROFILES_ACTIVE="hostile" \
      bash -c "$(declare -f run_with_tty); RUNNER='$RUNNER'; run_with_tty '$WORK/env' 'migrate ledgerline'" > "$out" 2>&1

  local problems=""
  [ -f "$capture/env" ] || problems="java was not reached; "
  if [ -f "$capture/env" ]; then
    local names
    names="$(cut -d= -f1 "$capture/env" | sort | tr '\n' ' ')"
    for name in $names; do
      case "$name" in
        PATH|HOME|DB_URL|DB_USERNAME|DB_PASSWORD|DB_OWNER_USERNAME|DB_OWNER_PASSWORD|JWT_SECRET) ;;
        SPRING_MAIN_WEB_APPLICATION_TYPE|SPRING_FLYWAY_ENABLED|PWD|SHLVL|_|OLDPWD) ;;
        *) problems+="unexpected variable $name; " ;;
      esac
    done
    for name in DB_URL DB_USERNAME DB_PASSWORD DB_OWNER_USERNAME DB_OWNER_PASSWORD JWT_SECRET; do
      grep -q "^$name=" "$capture/env" || problems+="missing $name; "
    done
    grep -qx "DB_URL=$GOOD_URL" "$capture/env" || problems+="DB_URL is not the validated URL; "
    grep -qx "SPRING_FLYWAY_ENABLED=true" "$capture/env" || problems+="SPRING_FLYWAY_ENABLED is not true; "
    grep -qx "SPRING_MAIN_WEB_APPLICATION_TYPE=none" "$capture/env" || problems+="not in migration mode; "
    grep -q "localhost\|hostile" "$capture/env" && problems+="a hostile value reached java; "
    [ "$(head -1 "$capture/args")" = "-jar" ] || problems+="unexpected java arguments; "
    grep -q "dummy-not-a-secret" "$capture/args" && problems+="a secret appeared in java's arguments; "
  fi
  if [ -z "$problems" ]; then
    echo "ok    environment isolation: java saw only the validated variables (hostile SPRING_*/JAVA_TOOL_OPTIONS blocked)"
  else
    echo "FAIL  environment isolation: $problems"
    tail -5 "$out"
    failures=$((failures + 1))
  fi
}
isolation_test

if [ "$failures" -eq 0 ]; then echo "All guard checks passed."; else echo "$failures guard check(s) FAILED."; exit 1; fi
