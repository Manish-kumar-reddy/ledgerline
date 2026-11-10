#!/usr/bin/env python3
"""Creates the LedgerLine demo users (one ACCOUNT_HOLDER, one ADMIN, one AUDITOR) on a deployed instance.

    python3 scripts/seed-demo.py /path/outside/repo/seed-demo.env        # or: export the variables, omit the file

Every credential comes from the environment (or a chmod-600 file outside the repository); nothing is hard-coded,
printed, or passed as a command-line argument.

  LEDGERLINE_API_BASE_URL   https://<render-backend-host>                      (the public API)
  DB_URL                    jdbc:postgresql://<neon-direct-host>/ledgerline?sslmode=require
  DB_OWNER_USERNAME         ledgerline_owner     (both only to promote ADMIN and AUDITOR with set-user-role.sql;
  DB_OWNER_PASSWORD         ...                   the runtime role cannot change roles)
  DEMO_HOLDER_EMAIL, DEMO_HOLDER_PASSWORD, DEMO_ADMIN_EMAIL, DEMO_ADMIN_PASSWORD,
  DEMO_AUDITOR_EMAIL, DEMO_AUDITOR_PASSWORD     (passwords: 12-72 UTF-8 bytes, all different)
  DEMO_INITIAL_DEPOSIT      optional rupee amount for the holder's first account (default 10000.00)

What it does, safely repeatable:
  1. Registers each user through the public API. An HTTP 409 (email exists) is accepted ONLY if signing in with the
     supplied password succeeds and /api/auth/me returns that email, so an account that someone else registered is
     never touched.
  2. Promotes ADMIN and AUDITOR with database/setup/set-user-role.sql as the migration owner over TLS, only if the
     signed-in user is currently ACCOUNT_HOLDER and its id matches the database row. Any other unexpected role stops.
  3. Signs in as the holder, opens "Demo Everyday" and "Demo Savings" if missing (POST /api/accounts), and deposits
     DEMO_INITIAL_DEPOSIT into "Demo Everyday" with the fixed Idempotency-Key "demo-seed-initial-deposit-v1".
     A rerun replays that deposit instead of posting it again. If "Demo Everyday" was recreated or the amount
     changed, the backend answers 409 and the script stops: nothing is posted twice.

LEDGERLINE_SEED_ALLOW_TEST_TARGET=1 additionally allows http://localhost with the database ledgerline_test_db (and
only that database), for local verification.
"""
import json
import os
import re
import stat
import subprocess
import sys
import urllib.error
import urllib.request
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
SET_ROLE_SQL = REPO / "database" / "setup" / "set-user-role.sql"
DEPOSIT_KEY = "demo-seed-initial-deposit-v1"
ACCOUNTS = ("Demo Everyday", "Demo Savings")
AMOUNT = re.compile(r"^(?!0+(\.0{1,2})?$)(0|[1-9][0-9]{0,8})(\.[0-9]{1,2})?$")
LOCAL_HOSTS = re.compile(r"^(localhost|127\..*|0\.0\.0\.0|::1|host\.docker\.internal|.*\.local)$", re.I)


class SeedError(Exception):
    pass


def fail(message):
    raise SeedError(message)


# ---- configuration --------------------------------------------------------------------------------------------------

def load_env_file(path):
    file = Path(path).expanduser().resolve()
    if not file.is_file():
        fail("environment file not found")
    if REPO in file.parents:
        fail("the environment file must be outside the repository")
    mode = stat.S_IMODE(file.stat().st_mode)
    if mode & 0o077:
        fail(f"environment file permissions are {oct(mode)}; run: chmod 600 '{file}'")
    values = {}
    for line in file.read_text().splitlines():
        line = line.strip()
        if line and not line.startswith("#") and "=" in line:
            key, value = line.split("=", 1)
            values[key.strip()] = value
    return values


def config(env):
    required = ["LEDGERLINE_API_BASE_URL", "DB_URL", "DB_OWNER_USERNAME", "DB_OWNER_PASSWORD",
                "DEMO_HOLDER_EMAIL", "DEMO_HOLDER_PASSWORD", "DEMO_ADMIN_EMAIL", "DEMO_ADMIN_PASSWORD",
                "DEMO_AUDITOR_EMAIL", "DEMO_AUDITOR_PASSWORD"]
    missing = [name for name in required if not env.get(name)]
    if missing:
        fail("missing: " + ", ".join(missing))
    test_mode = env.get("LEDGERLINE_SEED_ALLOW_TEST_TARGET") == "1"

    api = env["LEDGERLINE_API_BASE_URL"].rstrip("/")
    api_match = re.match(r"^(https?)://([^/:]+)(:\d+)?$", api)
    if not api_match:
        fail("LEDGERLINE_API_BASE_URL must be scheme://host[:port] with no path")
    api_local = bool(LOCAL_HOSTS.match(api_match.group(2)))

    db = re.match(r"^jdbc:postgresql://([^/:?]+)(?::(\d+))?/([A-Za-z0-9_]+)(?:\?(.*))?$", env["DB_URL"])
    if not db:
        fail("DB_URL is not jdbc:postgresql://host[:port]/database[?...]")
    host, port, name, query = db.group(1), db.group(2) or "5432", db.group(3), db.group(4) or ""
    ssl = re.search(r"(?:^|&)sslmode=(require|verify-ca|verify-full)(?:&|$)", query, re.I)
    db_local = bool(LOCAL_HOSTS.match(host))

    if test_mode:
        if name != "ledgerline_test_db":
            fail("test mode only allows the database ledgerline_test_db")
        if not (api_local and db_local):
            fail("test mode only allows a local API and a local database")
        sslmode = "disable"
    else:
        if api_match.group(1) != "https" or api_local:
            fail("LEDGERLINE_API_BASE_URL must be an https URL of the deployed backend (not localhost)")
        if db_local or name in ("ledgerline_db", "ledgerline_test_db"):
            fail("refusing a local database")
        if not ssl:
            fail("DB_URL must include sslmode=require (or verify-ca / verify-full)")
        sslmode = ssl.group(1).lower()

    users = []
    for role, prefix in (("ACCOUNT_HOLDER", "DEMO_HOLDER"), ("ADMIN", "DEMO_ADMIN"), ("AUDITOR", "DEMO_AUDITOR")):
        email = env[f"{prefix}_EMAIL"].strip().lower()
        password = env[f"{prefix}_PASSWORD"]
        if not re.match(r"^[\x21-\x7e]+@[\x21-\x7e]+\.[\x21-\x7e]+$", email) or len(email) > 254:
            fail(f"{prefix}_EMAIL is not a valid email address")
        if not 12 <= len(password.encode("utf-8")) <= 72:
            fail(f"{prefix}_PASSWORD must be 12 to 72 UTF-8 bytes")
        users.append({"role": role, "email": email, "password": password,
                      "name": {"ACCOUNT_HOLDER": "Demo Account Holder", "ADMIN": "Demo Administrator",
                               "AUDITOR": "Demo Auditor"}[role]})
    if len({u["email"] for u in users}) != 3:
        fail("the three demo emails must be different")
    if len({u["password"] for u in users}) != 3:
        fail("the three demo passwords must be different")

    deposit = env.get("DEMO_INITIAL_DEPOSIT", "10000.00")
    if not AMOUNT.match(deposit):
        fail("DEMO_INITIAL_DEPOSIT must be a positive rupee amount with at most 2 decimals")

    return {"api": api, "users": users, "deposit": deposit,
            "db": {"host": host, "port": port, "name": name, "user": env["DB_OWNER_USERNAME"],
                   "password": env["DB_OWNER_PASSWORD"], "sslmode": sslmode,
                   "sslrootcert": env.get("PGSSLROOTCERT", "")}}


# ---- HTTP -----------------------------------------------------------------------------------------------------------

class _RefuseRedirects(urllib.request.HTTPRedirectHandler):
    """Never follow a redirect: urllib would resend the Authorization header to the new location."""

    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None          # urllib then raises HTTPError with the 3xx status, handled in call()


_OPENER = urllib.request.build_opener(_RefuseRedirects)


def call(api, method, path, body=None, token=None, headers=None):
    data = None if body is None else json.dumps(body).encode()
    request = urllib.request.Request(api + path, data=data, method=method)
    request.add_header("Accept", "application/json")
    if data is not None:
        request.add_header("Content-Type", "application/json")
    if token:
        request.add_header("Authorization", "Bearer " + token)
    for key, value in (headers or {}).items():
        request.add_header(key, value)
    try:
        with _OPENER.open(request, timeout=90) as response:   # generous: the backend may be waking up
            return response.status, json.loads(response.read() or b"null"), dict(response.headers)
    except urllib.error.HTTPError as error:
        if 300 <= error.code < 400:
            fail(f"{path} answered with a redirect (HTTP {error.code}); refusing to follow it. "
                 "Check LEDGERLINE_API_BASE_URL (it must be the backend's final https URL).")
        payload = error.read()
        try:
            parsed = json.loads(payload) if payload else None
        except ValueError:
            parsed = None
        return error.code, parsed, dict(error.headers)
    except urllib.error.URLError as error:
        fail(f"cannot reach {path}: {error.reason}")


def title(payload):
    return (payload or {}).get("title", "no details") if isinstance(payload, dict) else "no details"


def sign_in(api, user):
    status, payload, _ = call(api, "POST", "/api/auth/login", {"email": user["email"], "password": user["password"]})
    if status == 401:
        fail(f"{user['email']} exists but the supplied password does not sign in: it may belong to someone else. "
             "Nothing was changed for this user.")
    if status != 200:
        fail(f"sign-in for {user['email']} failed with HTTP {status} ({title(payload)})")
    token = payload["accessToken"]
    status, me, _ = call(api, "GET", "/api/auth/me", token=token)
    if status != 200 or me.get("email") != user["email"]:
        fail(f"/api/auth/me did not confirm {user['email']} (HTTP {status})")
    return token, me


# ---- database (migration owner, TLS) --------------------------------------------------------------------------------

def psql(db, sql_args, stdin=None):
    env = dict(os.environ, PGPASSWORD=db["password"], PGSSLMODE=db["sslmode"], PGCONNECT_TIMEOUT="30")
    if db.get("sslrootcert"):
        env["PGSSLROOTCERT"] = db["sslrootcert"]      # e.g. "system" (libpq 16+) for sslmode=verify-full
    command = ["psql", "-X", "-q", "-At", "-v", "ON_ERROR_STOP=1", "-h", db["host"], "-p", db["port"],
               "-U", db["user"], "-d", db["name"]] + sql_args
    result = subprocess.run(command, input=stdin, env=env, capture_output=True, text=True)
    if result.returncode != 0:
        detail = result.stderr.strip().splitlines()[-1:] or ["(no output)"]
        fail(f"database step failed: {detail[0]}")
    return result.stdout.strip()


def promote(db, user, me):
    row_id = psql(db, ["-v", f"email={user['email']}", "-f", "-"], stdin="SELECT id FROM users WHERE email = :'email';\n")
    if row_id != me["id"]:
        fail(f"database row for {user['email']} does not match the signed-in user; not promoting")
    psql(db, ["-v", f"email={user['email']}", "-v", f"role={user['role']}", "-f", str(SET_ROLE_SQL)])


# ---- steps ----------------------------------------------------------------------------------------------------------

def ensure_user(cfg, user):
    api = cfg["api"]
    status, payload, _ = call(api, "POST", "/api/auth/register",
                              {"fullName": user["name"], "email": user["email"], "password": user["password"]})
    if status == 201:
        created = "registered"
    elif status == 409:
        created = "already registered (identity verified by signing in)"
    else:
        fail(f"registering {user['email']} failed with HTTP {status} ({title(payload)})")

    token, me = sign_in(api, user)
    if me["role"] != user["role"]:
        if me["role"] != "ACCOUNT_HOLDER" or user["role"] == "ACCOUNT_HOLDER":
            fail(f"{user['email']} has the unexpected role {me['role']}; not changing it")
        promote(cfg["db"], user, me)
        status, me, _ = call(api, "GET", "/api/auth/me", token=token)
        if status != 200 or me.get("role") != user["role"]:
            fail(f"promotion of {user['email']} to {user['role']} was not confirmed by the API")
        created += f", promoted to {user['role']}"
    print(f"  {user['role']:<14} {user['email']}: {created}")
    return token, me


def ensure_holder_accounts(cfg, token):
    api = cfg["api"]
    status, page, _ = call(api, "GET", "/api/accounts?page=0&size=100", token=token)
    if status != 200:
        fail(f"listing the holder's accounts failed with HTTP {status}")
    by_name = {}
    for account in page["items"]:
        by_name.setdefault(account["name"], account)
    for name in ACCOUNTS:
        if name not in by_name:
            status, created, _ = call(api, "POST", "/api/accounts", {"name": name}, token=token)
            if status != 201:
                fail(f"opening '{name}' failed with HTTP {status} ({title(created)})")
            by_name[name] = created
            print(f"  opened account '{name}' ({created['id']})")
        else:
            print(f"  account '{name}' already exists ({by_name[name]['id']})")

    everyday = by_name[ACCOUNTS[0]]["id"]
    status, result, headers = call(api, "POST", f"/api/accounts/{everyday}/deposits", {"amount": cfg["deposit"]},
                                   token=token, headers={"Idempotency-Key": DEPOSIT_KEY})
    replayed = headers.get("Idempotent-Replayed") == "true"
    if status == 201:
        print(f"  initial deposit of Rs {cfg['deposit']} into '{ACCOUNTS[0]}': "
              f"{'already done (replayed, not posted again)' if replayed else 'posted'}; balance Rs {result['balance']}")
    elif status == 422:
        print(f"  initial deposit refused ({result['reason']}); recorded on the statement")
    elif status == 409:
        fail("the initial-deposit key was already used with different details (account recreated or amount changed); "
             "nothing was posted. Deposit manually through the app if needed.")
    else:
        fail(f"initial deposit failed with HTTP {status} ({title(result)})")


def main(argv):
    env = dict(os.environ)
    if len(argv) > 1:
        env.update(load_env_file(argv[1]))
    cfg = config(env)
    print(f"Seeding demo users on {cfg['api']} (database {cfg['db']['name']} on {cfg['db']['host']}):")
    tokens = {}
    for user in cfg["users"]:
        tokens[user["role"]], _ = ensure_user(cfg, user)
    ensure_holder_accounts(cfg, tokens["ACCOUNT_HOLDER"])
    print("Done. Passwords were not printed; share them only through the private submission channel.")


if __name__ == "__main__":
    try:
        main(sys.argv)
    except SeedError as error:
        print(f"seed-demo: {error}", file=sys.stderr)
        sys.exit(1)
