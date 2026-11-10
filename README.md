# ledgerline
Secure money movement and double-entry ledger system built with Spring Boot, PostgreSQL and React

> **Status: early development.** Working so far: the PostgreSQL foundation with Flyway migrations, and
> authentication (registration, login, JWT access tokens and a protected `/api/auth/me`). No financial features
> exist yet. Features are added phase by phase, and this README describes what actually works at each stage.

## Project structure

```text
ledgerline/
├── backend/            Spring Boot API (Java 21, Maven Wrapper); Dockerfile for deployment
├── database/setup/     Owner-only SQL: local and production roles and databases; set a user's role
├── frontend/           React app (Vite); vercel.json for deployment
├── scripts/            Production migration runner and demo-account seeding (secrets from the environment only)
├── PROMPTS.md          Log of AI assistance used in this project
├── THREATS.md          Threat model
└── README.md
```

## Prerequisites

| Tool | Version | Notes |
|---|---|---|
| Java | 21 | The backend targets Java 21 |
| Node.js | 20.19+ or 22.12+ | Required by Vite |
| PostgreSQL | 16+ | The setup script uses `\getenv`, which needs psql 15 or newer |

Maven does not need to be installed: the backend includes the Maven Wrapper (`mvnw`), which downloads the
correct Maven version into `~/.m2` on first use.

## Database setup

LedgerLine uses two PostgreSQL roles. The application's runtime role, `ledgerline_app`, cannot create or modify
the schema under the configured permissions; only the migration owner can:

| Role | Used by | Can do |
|---|---|---|
| `ledgerline_owner` | Flyway (migrations) | Owns both databases and every table; creates and changes the schema |
| `ledgerline_app` | The running application | Connect, plus only the table privileges that migrations grant it. It owns nothing and cannot create tables. |

Two databases: `ledgerline_db` for the application and `ledgerline_test_db` for integration tests.

> **Local authentication note:** the developer's local PostgreSQL (`pg_hba.conf`) currently uses `trust`
> authentication. Local connections are accepted without checking any password. So locally, any process on the
> machine can connect as either role, and password authentication has **not** been tested. The role separation
> above limits what the application's own connection can do; it does not stop someone on the same machine from
> connecting as `ledgerline_owner`. Passwords are only enforced on a server configured with password
> authentication (for example `scram-sha-256`).

1. Create `backend/.env` from the template. The file is ignored by Git. Generate each password separately:

   ```bash
   cd backend
   cp .env.example .env
   openssl rand -base64 48 | tr -d '/+=\n' | cut -c1-40   # run twice: DB_OWNER_PASSWORD, then DB_PASSWORD
   ```

2. Create the roles and databases once, as a PostgreSQL superuser. The script stops without changing anything
   if any of them already exist:

   ```bash
   cd ..   # repository root
   LEDGERLINE_OWNER_PASSWORD="$(sed -n 's/^DB_OWNER_PASSWORD=//p' backend/.env)" \
   LEDGERLINE_APP_PASSWORD="$(sed -n 's/^DB_PASSWORD=//p' backend/.env)" \
     psql -h 127.0.0.1 -d postgres -X -f database/setup/create-database.sql
   ```

Tables are never created by hand or by Hibernate. Flyway applies the migrations in
`backend/src/main/resources/db/migration` when the backend starts. Hibernate only validates the entities
against the tables (`spring.jpa.hibernate.ddl-auto=validate`).

| Migration | What it does |
|---|---|
| `V1__restrict_public_schema.sql` | Only the owner may create objects in schema `public`; the app role gets `USAGE` |
| `V2__assert_public_schema_privileges.sql` | Fails the migration unless V1 really took effect (reads the schema ACL, where PUBLIC is grantee 0), and checks the app role is not the owner, a superuser or a member of the schema owner |
| `V3__create_users.sql` | The `users` table and the app role's privileges on it (below) |
| `V4__create_accounts.sql` | The `accounts` table and the app role's privileges on it (below) |
| `V5__create_ledger.sql` | Balances, the double-entry ledger, idempotency keys, refused attempts, statement lines, invariant triggers, the posting functions and reconciliation (see Ledger) |
| `V6__harden_ledger.sql` | Hardening after the V5 audit: statement times taken after the locks, the initiator's user row locked while posting, stronger outcome checks, a single SYSTEM account, no empty descriptions, restored session settings (see Ledger) |

**What `ledgerline_app` may do on `users`** (verified with real SQL attempts in `UserTablePrivilegesIT`):

| Allowed | Refused by PostgreSQL |
|---|---|
| `SELECT` | `UPDATE` of any column, including `role` and `enabled` |
| `INSERT` of `id`, `full_name`, `email`, `password_hash` only. `role`, `enabled` and `created_at` always take their defaults (`ACCOUNT_HOLDER`, `true`, `now()`). | `INSERT` naming `role`, `enabled` or `created_at`; `DELETE`; `TRUNCATE`; `ALTER`/`DROP` |

Check constraints also make PostgreSQL reject a password that is not a BCrypt hash, an email that is not
trimmed and lower-case, and a blank name.

**The `accounts` table** (V4, extended by V5): `id`, `owner_id` (foreign key to `users.id`), `kind`
(`CUSTOMER`, or `SYSTEM` for the single simulated settlement account, which has no owner), `name` (1–100
characters, not blank), `currency` (default `INR`), `status` (default `ACTIVE`; `FROZEN` and `CLOSED` can only be
set by the database owner), `balance_minor` (paise, maintained only by the posting functions), `last_seq` (number
of statement lines) and `created_at`. Check constraints: a customer balance is between 0 and 1,000,000,000 paise;
the settlement balance is never positive. **INR only:** a check constraint allows no other currency, even for the
owner role; supporting more currencies would be a deliberate migration.

**What `ledgerline_app` may do on `accounts`** (verified in `AccountTablePrivilegesIT`):

| Allowed | Refused by PostgreSQL |
|---|---|
| `SELECT` | `UPDATE` of any column, including `owner_id`, `status` and `balance_minor` |
| `INSERT` of `id`, `owner_id`, `name` only. Everything else takes its default (a new account is always an empty `CUSTOMER` account). | `INSERT` naming any other column; `DELETE`; `TRUNCATE`; `ALTER`/`DROP` |

PUBLIC has no privileges on the table. The foreign key is checked by PostgreSQL as the table owner, so the app
role needs no extra privilege on `users`. The database cannot tell *which* user is making a request, so it does
not check that `owner_id` is the caller: the application does (see Authorization). Row-level security is not used
(see Known limitations). The ledger tables are described under Ledger.

**Migration placeholder safety.** Migrations use the `${app_role}` Flyway placeholder for the runtime role's
name. Flyway substitutes it as raw text, so `MigrationRoleValidator` checks it in Java before Flyway runs: it
must match `^[a-z_][a-z0-9_]{0,62}$` and differ from the Flyway user, or the application refuses to start.
Role names must therefore be lower-case. Migrations also quote the name with `format('%I', …)`.

### Granting ADMIN or AUDITOR

There is no API for this. A person first registers normally (and becomes an `ACCOUNT_HOLDER`); then the
database owner promotes them:

```bash
psql -h 127.0.0.1 -U ledgerline_owner -d ledgerline_db -X \
  -v email='person@example.com' -v role=ADMIN -f database/setup/set-user-role.sql
```

The script never handles a password. It changes nothing (and exits non-zero) unless the role is `ADMIN`,
`AUDITOR` or `ACCOUNT_HOLDER` and exactly one user has that email. Run as `ledgerline_app` it fails with
`permission denied`. To disable a user, the owner runs `UPDATE users SET enabled = false WHERE email = '…'`;
the user's existing tokens stop working on their next request.

## Configuration

| Variable | Purpose |
|---|---|
| `DB_URL` | JDBC URL of the application database, e.g. `jdbc:postgresql://localhost:5432/ledgerline_db` |
| `DB_USERNAME`, `DB_PASSWORD` | Runtime role (`ledgerline_app`), used by the application's connection pool |
| `DB_OWNER_USERNAME`, `DB_OWNER_PASSWORD` | Migration owner (`ledgerline_owner`), used only by Flyway |
| `TEST_DB_URL` | JDBC URL of `ledgerline_test_db`, used only by integration tests |
| `JWT_SECRET` | Signs access tokens (HS256). Base64 of at least 32 random bytes: `openssl rand -base64 48`. Changing it signs everyone out. |
| `CORS_ALLOWED_ORIGINS` | Optional. Browser origins allowed to call the API; default `http://localhost:5173` |
| `SPRING_FLYWAY_ENABLED` | Optional. `false` stops the application from running migrations (see Production) |

Rate limits are set in `application.properties`. Each setting can be overridden by an environment variable, for
example `LEDGERLINE_LOGIN_RATE_LIMIT_MAX_FAILURES_PER_EMAIL=10`. (Exact policy: "Rate limiting" below.)

| Setting | Default | Meaning |
|---|---|---|
| `ledgerline.login-rate-limit.enabled` | `true` | Turns the login limiter on or off |
| `ledgerline.login-rate-limit.max-failures-per-email` | `5` | Failed plus in-progress login attempts for one email per window |
| `ledgerline.login-rate-limit.max-failures-per-ip` | `50` | Failed plus in-progress login attempts from one client IP per window |
| `ledgerline.login-rate-limit.window` | `15m` | Login window, counted from the first counted attempt |
| `ledgerline.login-rate-limit.max-tracked-keys` | `100000` | Memory cap on tracked emails and IPs (at least 2); when full, new keys are refused |
| `ledgerline.registration-rate-limit.enabled` | `true` | Turns the registration limiter on or off |
| `ledgerline.registration-rate-limit.max-attempts-per-ip` | `10` | Registration attempts (successful and duplicate included) per client IP per window |
| `ledgerline.registration-rate-limit.window` | `1h` | Registration window |
| `ledgerline.registration-rate-limit.max-tracked-keys` | `100000` | Memory cap on tracked IPs; when full, new IPs are let through uncounted |

- **Local development:** `application.properties` imports `backend/.env` with
  `spring.config.import=optional:file:.env[.properties]`. The path is relative to the folder the app starts in,
  which is `backend/` when you use `./mvnw spring-boot:run`.
- **Production:** set the same names as real environment variables. They take precedence over `.env`, and the
  file is optional, so it is not needed on a server. If a variable is missing, startup fails with
  `Could not resolve placeholder`.
- **Secrets at startup:** a missing, non-Base64 or too-short `JWT_SECRET` stops the application with a message
  that names the setting but never contains the value.
- **Migration credentials, local development:** Flyway runs at application startup with the owner credentials,
  so locally the owner password is present whenever the app runs. Production should not do this; see below.

## Production

In production the **long-running application must only hold `ledgerline_app` credentials**. Migrations run as a
separate, short-lived step with the owner account. The local setup above is unchanged; this is purely
configuration.

**1. Migration step** (run on each deploy, before the new application version starts). It is the same jar with
no web server: it applies pending Flyway migrations, checks the entities against the schema, and exits.

```bash
# Environment for the migration step only (set these in the deployment tool, never on the command line):
#   DB_URL, DB_USERNAME (= ledgerline_app, used as the ${app_role} placeholder), DB_PASSWORD,
#   DB_OWNER_USERNAME (= ledgerline_owner), DB_OWNER_PASSWORD, JWT_SECRET
SPRING_MAIN_WEB_APPLICATION_TYPE=none java -jar ledgerline-backend.jar
```

Exit code 0 means every migration was applied and validated. **The deployment must run this step to a
successful exit before starting the new application version, and must stop on any non-zero exit** (for example
an invalid `app_role`, a failed migration, or V2 finding wrong schema privileges).

**2. Application** (long-running):

```bash
# Environment: DB_URL, DB_USERNAME (= ledgerline_app), DB_PASSWORD, JWT_SECRET, SPRING_FLYWAY_ENABLED=false
# DB_OWNER_USERNAME and DB_OWNER_PASSWORD are NOT set.
java -jar ledgerline-backend.jar
```

With Flyway disabled, the application never connects as the owner and never needs the owner password.

**What the application's own startup check does and does not cover.** `spring.jpa.hibernate.ddl-auto=validate`
makes Hibernate check, at startup, that every mapped table and column exists with a compatible type. It does
**not** check:
- grants and privileges, such as V3's column-level `INSERT` grant;
- constraints, defaults and checks, such as the BCrypt-format check or column defaults;
- indexes;
- whether all migrations have been applied: it does not read Flyway's history;
- migrations that only verify state, such as V2.

So a new application version can start against a database that is missing a privilege-only or constraint-only
migration, and fail later, or behave more permissively. The migration step's successful exit, not Hibernate,
is what guarantees the schema is current.

**Other production settings:**
- Use password authentication (`scram-sha-256`) in `pg_hba.conf`, never `trust`, and TLS to the database
  (`sslmode=require` or stricter in `DB_URL`).
- Behind a reverse proxy or load balancer, the rate limiters need the real client IP:
  - Set `server.forward-headers-strategy=native`. Tomcat then accepts `X-Forwarded-For` only from trusted
    proxies (by default, private and loopback addresses; set `server.tomcat.remoteip.internal-proxies` to exactly
    your proxies' addresses). Do not use `framework`: it trusts the header from any caller.
  - Make the proxy overwrite (not append to) `X-Forwarded-For`.
  - Make the application port reachable only from the proxy (firewall or private network). A client that can
    reach it directly, or from an address treated as a trusted proxy, can fake its IP and avoid the per-IP limits.
  - Without this, every client appears to have the proxy's address and shares one per-IP limit.
- Store `JWT_SECRET` and the database passwords in the platform's secret store, never in the repository or
  image.

How this was verified locally (the jar run from an empty folder, so `backend/.env` was not loaded):
- The application with `SPRING_FLYWAY_ENABLED=false` and no owner variables started with no Flyway activity, and
  register, login and `/api/auth/me` worked.
- The migration step exited 0 on its own with "Schema "public" is up to date".
- The migration step with an unsafe `DB_USERNAME` exited 1 with "Refusing to run migrations", before Flyway
  connected.
- No secret appeared in any output.

## Deployment: Vercel (frontend), Render (backend), Neon (PostgreSQL)

> **Status:** prepared, not yet deployed. Live URLs below are placeholders until a real deployment is verified.
>
> - Frontend: `https://<your-project>.vercel.app` *(to be filled in after deployment)*
> - Backend: `https://<your-service>.onrender.com` *(to be filled in after deployment)*

Never use a local database for production, and never put a secret in the repository, a `VITE_` variable, a
command-line argument or a log.

### Environment variables: which service gets what

| Variable | Render (app) | Migration step (your machine) | Seeding (your machine) | Vercel build | Secret |
|---|---|---|---|---|---|
| `DB_URL` (`jdbc:postgresql://<neon-direct-host>/ledgerline?sslmode=require`) | yes | yes | yes | – | keep private |
| `DB_USERNAME` (`ledgerline_app`) | yes | yes (as `${app_role}`) | – | – | no |
| `DB_PASSWORD` | yes | yes | – | – | **yes** |
| `DB_OWNER_USERNAME` (`ledgerline_owner`) | **never** | yes | yes (role promotion) | – | no |
| `DB_OWNER_PASSWORD` | **never** | yes | yes (role promotion) | – | **yes** |
| `JWT_SECRET` (new: `openssl rand -base64 48`) | yes | yes (start-up check) | – | – | **yes** |
| `SPRING_FLYWAY_ENABLED=false` | yes | must not be `false` | – | – | no |
| `CORS_ALLOWED_ORIGINS` (exact Vercel URL) | yes | – | – | – | no |
| `SERVER_FORWARD_HEADERS_STRATEGY=native` | yes | – | – | – | no |
| `SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE` (e.g. `5`) | yes | – | – | – | no |
| `PORT` | injected by Render; the image maps it to `SERVER_PORT` | – | – | – | no |
| `LEDGERLINE_API_BASE_URL`, `DEMO_*_EMAIL`, `DEMO_*_PASSWORD` | – | – | yes | – | **passwords yes** |
| `VITE_API_BASE_URL` (the Render URL) | – | – | – | yes | **no: bundled into public JavaScript** |

Files holding secrets on your machine live **outside the repository** with `chmod 600` (for example
`~/.ledgerline/production-migrate.env` and `~/.ledgerline/seed-demo.env`).

### 1. Neon: dedicated production database

1. Create a **new, dedicated** Neon project (PostgreSQL 16 or later). Use its **direct** (not `-pooler`) host.
2. Create the roles and database **with SQL** (roles made in the Neon Console or API become members of
   `neon_superuser`, which migration V2 refuses for the application role):

   ```bash
   psql "host=<neon-direct-host> dbname=neondb user=neondb_owner sslmode=require" -X \
        -f database/setup/create-production-database.sql
   ```

   It creates `ledgerline_owner` (owns database `ledgerline`; Flyway only) and `ledgerline_app` (runtime; may only
   connect), with no superuser, CREATEDB, CREATEROLE or BYPASSRLS. It fails without changing anything if they
   already exist, and prints the resulting attributes for you to check.
3. Set each password. Neon **does not accept pre-hashed passwords**, so psql's `\password` cannot be used; this
   script reads the password from an environment variable (never a command-line argument or a file), sends it in a
   single `ALTER ROLE` statement over TLS, and echoes nothing. Do not add psql's `-L` log option. Neon could still
   record that statement if its server-side statement logging is enabled (see THREATS.md):

   ```bash
   openssl rand -base64 48 | tr -d '/+=\n' | cut -c1-40    # generate; store in your password manager
   read -rs LEDGERLINE_NEW_PASSWORD && export LEDGERLINE_NEW_PASSWORD
   psql "host=<neon-direct-host> dbname=neondb user=neondb_owner sslmode=require" -X \
        -v role=ledgerline_owner -f database/setup/set-production-role-password.sql
   unset LEDGERLINE_NEW_PASSWORD                            # repeat for -v role=ledgerline_app
   ```
4. Check: connecting as `ledgerline_app` to `ledgerline` with `sslmode=require` works, and `create table x (i int)`
   is refused.
5. **If step 2 stopped part-way** (for example the roles were created but `CREATE DATABASE` failed): follow the
   recovery steps at the top of `create-production-database.sql`. They apply **only** to the new, empty project
   dedicated to LedgerLine (drop the two new roles if the database does not exist; otherwise finish the remaining
   grants by hand). Never drop roles or databases in a project that holds anything else; if in doubt, delete the
   dedicated project and start a new one.

**TLS certificate verification (deployment prerequisite, not yet verified).** `sslmode=require` encrypts the
connection but does not prove the server's identity. Full verification is expected to work with these settings,
but has **not** been tested (the local PostgreSQL has TLS off, and Neon has not been accessed):
- Java (Render, migration step): `DB_URL=jdbc:postgresql://<host>/ledgerline?sslmode=verify-full&sslfactory=org.postgresql.ssl.DefaultJavaSSLFactory`
  (driver 42.7.14 ships this factory; it uses the JVM's default trusted certificate authorities).
- psql (setup scripts, `seed-demo.py`): `sslmode=verify-full` with `PGSSLROOTCERT=system` (libpq 16 or later; the
  local psql is 18.6). Put `PGSSLROOTCERT=system` in `seed-demo.env` for the seeder.
Test both against Neon before relying on them; if either fails, `sslmode=require` remains in use and this stays a
documented limitation. The scripts accept `require`, `verify-ca` and `verify-full`.

### 2. Migrations (from your machine, before every backend deploy)

```bash
chmod 600 ~/.ledgerline/production-migrate.env     # DB_URL, DB_USERNAME, DB_PASSWORD, DB_OWNER_*, JWT_SECRET
scripts/migrate-production.sh ~/.ledgerline/production-migrate.env
```

The script refuses local hosts and the local database names, requires `sslmode=require` (or stricter), requires
`DB_USERNAME=ledgerline_app` and `DB_OWNER_USERNAME=ledgerline_owner` exactly, accepts `SPRING_FLYWAY_ENABLED` only
if unset or exactly `true`, requires the file to be outside the repository and `chmod 600`, asks you to type
`migrate <database>`, builds the jar, and runs it in migration mode from an empty folder with an **empty
environment** (`env -i`): Java receives only `PATH`, `HOME`, the six validated variables (passed through a pipe,
never as arguments), `SPRING_MAIN_WEB_APPLICATION_TYPE=none` and `SPRING_FLYWAY_ENABLED=true`. Inherited or
file-supplied `SPRING_DATASOURCE_URL`, `SPRING_FLYWAY_URL`, `SPRING_FLYWAY_USER`, `SPRING_APPLICATION_JSON`,
`SPRING_CONFIG_*` or `JAVA_TOOL_OPTIONS` therefore cannot redirect it. Exit 0 means V1–V6 were
applied and validated; anything else stops: do not deploy. Running it again prints "Schema "public" is up to date".
Its refusals and the environment isolation are covered by `scripts/test-migrate-production-guards.sh` (dummy values
only; never connects to a database; the isolation case uses a fake `java` that records what it receives).

### 3. Render: backend

- New **Web Service** from the private GitHub repository, **Runtime: Docker**, **Root Directory: `backend`**
  (uses `backend/Dockerfile`: Java 21 JRE, non-root user, no secrets in the image).
- Environment: the "Render (app)" column above. Never the owner variables.
- Health check path: `/api/health`.
- Check before connecting the frontend:
  `curl https://<render-host>/api/health` → `{"status":"UP",…}`; register and sign in a throwaway user with `curl`.

`/api/health` only shows that the web server is running; it does **not** check the database. A working sign-in is
the real readiness check.

### 4. Vercel: frontend

- New project from the repository, **Root Directory: `frontend`**, build `npm run build`, output `dist`.
- Environment: `VITE_API_BASE_URL=https://<render-host>` (public).
- **Before deploying, edit `frontend/vercel.json`:** replace `https://REPLACE-WITH-RENDER-BACKEND-HOST` in
  `connect-src` with the real Render origin. Until then the browser blocks every API call (it fails closed).
- `vercel.json` also provides the SPA fallback (deep links like `/accounts/<id>`) and security headers: a
  Content-Security-Policy with no `unsafe-inline` or `unsafe-eval` (the Vite build has no inline scripts or
  styles), `frame-ancestors 'none'`, `nosniff`, `no-referrer`, a restrictive Permissions-Policy and HSTS. The E2E
  tests run against these same headers (`vite preview` serves them).
- Then set Render's `CORS_ALLOWED_ORIGINS` to the exact Vercel production URL and redeploy the backend.

### 5. Demo accounts

```bash
chmod 600 ~/.ledgerline/seed-demo.env
python3 scripts/seed-demo.py ~/.ledgerline/seed-demo.env
```

`seed-demo.env` (outside the repository) holds `LEDGERLINE_API_BASE_URL`, `DB_URL`, `DB_OWNER_USERNAME`,
`DB_OWNER_PASSWORD` and `DEMO_HOLDER_EMAIL/PASSWORD`, `DEMO_ADMIN_EMAIL/PASSWORD`, `DEMO_AUDITOR_EMAIL/PASSWORD`
(three different passwords, 12–72 bytes; optional `DEMO_INITIAL_DEPOSIT`, default `10000.00`). The script:

- registers the three users through the public API; an existing email is accepted only if signing in with the
  supplied password works and `/api/auth/me` confirms it (an account registered by someone else is never touched);
- promotes the admin and auditor with `set-user-role.sql` as `ledgerline_owner` over TLS, only from
  `ACCOUNT_HOLDER` and only after matching the database row to the signed-in user;
- opens "Demo Everyday" and "Demo Savings" for the holder and deposits the initial amount with the fixed
  `Idempotency-Key` `demo-seed-initial-deposit-v1`, so reruns replay it instead of posting again. If that account
  was recreated or the amount changed, the backend answers 409 and the script stops (nothing posted twice).

It never prints passwords or tokens, and it refuses HTTP redirects (so a sign-in token is never forwarded to
another host). `python3 scripts/test_seed_demo.py` tests the redirect refusal and the input checks without a
backend or database. Verified locally against `ledgerline_test_db` in its test-only mode
(`LEDGERLINE_SEED_ALLOW_TEST_TARGET=1`): first run created and promoted everything; second run changed nothing and
replayed the deposit; a pre-existing email with a different password was refused and its role left unchanged.
**Demo credentials are not in this repository**; they are provided privately with the submission.

### 6. Live verification checklist (record the real results)

- [ ] Health 200; time the first request after an idle period (cold start).
- [ ] Register (201); duplicate email (409); invalid fields shown.
- [ ] Sign in; wrong password gives the generic message; reload signs out.
- [ ] Open account; deposit ₹500; withdraw ₹200; withdraw ₹1,000 → **refused**, balance unchanged, shown on the
      statement; transfer ₹100 to another account; both statements' running balances add up.
- [ ] Retry after a network failure (DevTools offline) posts once.
- [ ] Holder → oversight pages show "Not permitted" and the API returns 403; auditor/admin have no money actions
      (API 403); another user's account ID gives 404.
- [ ] Reconciliation: "Books balanced", total ₹0.00.
- [ ] Failed sign-ins from two different networks are counted separately (forwarded client IP works; see
      THREATS.md).
- [ ] No secrets in the browser bundle, Render logs or Git.

### Free-tier notes (platform-dependent: check the providers' current terms)

- Render's free web services are expected to **sleep when idle**; the first request then waits while the Java
  application starts (possibly a minute or more). The sign-in page says so if signing in is slow. Warm the service
  (open `/api/health`) before a demo. Small instances also make BCrypt sign-ins slower (cost unchanged).
- The in-memory rate limits reset whenever the instance restarts or sleeps.
- Neon suspends idle compute; the first query after a pause is slower. Check connection limits for your compute
  size (the app uses a small pool; `ledgerline_app` is limited to 20 connections).
- Render's free tier is not expected to offer a pre-deploy command, so migrations run from your machine (step 2).

### Judge quick-start

1. Open the frontend URL (wait up to a minute the first time if the backend was asleep).
2. Sign in with the **demo holder** credentials provided with the submission (or register a new account).
3. Open "Demo Everyday": deposit, withdraw, try a withdrawal larger than the balance (refused and recorded), and
   transfer to "Demo Savings" by pasting its account ID. Check the statement's running balances.
4. Sign out, sign in as the **auditor** or **admin**: browse all accounts and statements (read-only) and open
   Reconciliation.
5. Note: five wrong passwords for one email lock it for 15 minutes (rate limit); please copy credentials carefully.

## Running

Backend:

```bash
cd backend
./mvnw spring-boot:run        # Windows: mvnw.cmd spring-boot:run
```

Then open <http://localhost:8080/api/health>. It should return `{"status":"UP","service":"ledgerline-backend"}`.

Frontend:

```bash
cd frontend
npm install
npm run dev                   # VITE_API_BASE_URL defaults to http://localhost:8080
```

Then open <http://localhost:5173> (the backend's default CORS origin). Note: starting the backend with the default
configuration runs Flyway against `ledgerline_db`, bringing it to the latest migration.

## Frontend (Phase 6)

React 19, Vite, React Router and Axios, with plain CSS (no UI library). The backend is the only security
authority: the frontend's role checks only decide what to show, and every request is authorized again by the API.

| Screen | Path | Who | Uses |
|---|---|---|---|
| Sign in, Create account | `/login`, `/register` | anyone | `POST /api/auth/login`, `POST /api/auth/register`, `GET /api/auth/me` |
| My accounts | `/accounts` | ACCOUNT_HOLDER | `GET/POST /api/accounts` |
| Account: balance, deposit, withdraw, transfer, statement | `/accounts/:id` | ACCOUNT_HOLDER | `GET /api/accounts/{id}`, `POST …/deposits`, `…/withdrawals`, `…/transfers`, `GET …/statement` |
| All accounts (read-only) | `/oversight/accounts` | ADMIN, AUDITOR | `GET /api/oversight/accounts` |
| Any account's statement (read-only) | `/oversight/accounts/:id` | ADMIN, AUDITOR | `GET /api/oversight/accounts/{id}/statement` |
| Reconciliation | `/oversight/reconciliation` | ADMIN, AUDITOR | `GET /api/oversight/reconciliation` |

How it behaves:
- **Session:** the access token is kept **in memory only** (never `localStorage`, cookies or the URL). Reloading the
  page signs you out. There is no logout endpoint: signing out discards the token. The session ends automatically
  when the 15-minute token expires, and any 401 from a protected request returns to the sign-in page ("Your session
  has ended"). A 403 is shown as "Not permitted".
- **Money:** amounts are typed as text, checked against the same rule as the backend, and sent as the exact string
  (`"1500.50"`). Display uses integer paise with Indian digit grouping (`₹1,00,00,000.00`); no floating point.
- **Idempotency:** each money request carries an `Idempotency-Key`. A retry after a network error, a 5xx or a 503
  ("Ledger busy") resends the **same** key, so it can never post twice; changing the form or starting a new
  operation uses a new key. Withdrawals and transfers ask for confirmation first.
- **Outcomes:** posted (green, new balance), refused (red, reason, unchanged balance, recorded on the statement),
  409 (key reused with different details: nothing posted), 503 (busy: retry offered).
- **Statements:** debit and credit columns, running balance, refused attempts with their reason, the initiator
  ("You" or a shortened user id) and the other account. Print-friendly.
- **Oversight:** ADMIN and AUDITOR see the same read-only views (the backend gives them the same access). The
  settlement account is labelled "Settlement (system)". Reconciliation shows "Books balanced" or each difference.
- **Transfers** need the recipient's full account ID (there is no lookup by name or email); each account shows its ID
  with a Copy button.
- Responsive down to small phones (tables become labelled rows), visible keyboard focus, `prefers-reduced-motion`.

## Authentication API

| Method and path | Access | Success | Failures |
|---|---|---|---|
| `GET /api/health` | Public | 200 `{"status":"UP",…}` | |
| `POST /api/auth/register` | Public | 201 user (always `ACCOUNT_HOLDER`), `Location: /api/auth/me` | 400 invalid or unknown field; 409 email already registered; 429 `Too many registration attempts` with `Retry-After` |
| `POST /api/auth/login` | Public | 200 `{"accessToken":"…","tokenType":"Bearer","expiresIn":900}` | 401 `Invalid email or password` for every authentication failure (unknown email, wrong password, disabled account); 500 for unexpected server or database errors, which are never reported as invalid credentials; 429 `Too many login attempts` with `Retry-After` |
| `GET /api/auth/me` | Bearer token | 200 the current user, read from the database | 401 with `WWW-Authenticate: Bearer` (no token) or `Bearer error="invalid_token"` |

```bash
curl -X POST localhost:8080/api/auth/register -H 'Content-Type: application/json' \
  -d '{"fullName":"Asha Rao","email":"asha@example.com","password":"correct horse battery staple"}'
curl -X POST localhost:8080/api/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"asha@example.com","password":"correct horse battery staple"}'
curl localhost:8080/api/auth/me -H "Authorization: Bearer <accessToken>"
```

Errors are RFC 9457 problem responses (`application/problem+json`). Validation errors list each field and rule,
never the rejected value.

**Unknown JSON properties:** the registration and login requests are annotated `@RejectUnknownProperties`, so a
property they do not define (for example `"role"`) gives 400. This is per type, not global: other request types
keep Jackson's default and ignore unknown properties. `StrictJsonModule` enforces it.

## Accounts API (Phase 4)

| Method and path | Anonymous | ACCOUNT_HOLDER | ADMIN | AUDITOR |
|---|---|---|---|---|
| `POST /api/accounts` body `{"name":"…"}` | 401 | 201, owned by the caller, `Location: /api/accounts/{id}` | 403 | 403 |
| `GET /api/accounts?page=&size=` | 401 | 200, own accounts only | 403 | 403 |
| `GET /api/accounts/{id}` | 401 | 200 if own; **404** otherwise | 403 | 403 |
| `GET /api/oversight/accounts?page=&size=` | 401 | 403 | 200, all accounts | 200, all accounts |
| `GET /api/oversight/accounts/{id}` | 401 | 403 | 200 or 404 | 200 or 404 |
| Any other method or path (e.g. `PUT`/`DELETE` on an account) | 401 | 403 | 403 | 403 |

- An account is `{"id","ownerId","kind","name","currency","status","balance","balanceMinor","createdAt"}`
  (`balance` is rupees as a string, `balanceMinor` paise). No user name, email or hash is ever included. The
  oversight list also shows the settlement account (`kind: SYSTEM`, no owner).
- Lists are pages: `{"items":[…],"page","size","totalItems","totalPages"}`, oldest first. `page` is 0–10,000
  (default 0) and `size` is 1–100 (default 20); anything else, or a non-number, is 400. The sort order is fixed.
- Creating an account accepts only `name`. `ownerId`, `id`, `currency`, `status`, `role` or any other property
  gives 400 and nothing is created. Currency is always INR and status always ACTIVE.
- A malformed id is 400; the response does not repeat it.
- ADMIN and AUDITOR are read-only and cannot open customer accounts or move money. There is no API to change a
  role, freeze, close or delete an account, or list users.

```bash
curl -X POST localhost:8080/api/accounts -H "Authorization: Bearer <token>" -H 'Content-Type: application/json' \
  -d '{"name":"Savings"}'
curl localhost:8080/api/accounts -H "Authorization: Bearer <token>"
```


## Ledger (Phase 5): balances, money movement and statements

### Financial model

Conventional **double-entry bookkeeping**, from the books of the (simulated) bank, recorded in one consistent
signed form:

- Every journal transaction has exactly **one DEBIT and one CREDIT of the same amount on two different accounts**.
- Every account's balance is defined the same way: **balance = credits − debits**, in paise (`bigint`).
  - A **customer account** is money the bank owes the customer (a liability): a credit increases it, and it can
    never go below 0 (no overdraft).
  - **`SETTLEMENT_CASH`** (`a0000000-0000-4000-8000-000000000001`) is the simulated external cash account (an
    asset). It is not connected to any real bank. With the same definition its balance is never positive; its
    magnitude is the cash held. In conventional terms that is a debit balance of the same size.
- Because every transaction's debits equal its credits, **the sum of all balances is always 0**.

Worked example (start A = 0, B = 0, CASH = 0; amounts in paise). This exact sequence is an integration test
(`LedgerApiIT.theWorkedExampleFromTheReadme`):

| Transaction | Entry | Direction | Signed change | Balance after | Total of all balances |
|---|---|---|---|---|---|
| Deposit ₹500 into A | CASH | DEBIT 50,000 | −50,000 | CASH −50,000 | |
| | A | CREDIT 50,000 | +50,000 | A 50,000 | 0 → 0 |
| Withdraw ₹200 from A | A | DEBIT 20,000 | −20,000 | A 30,000 | |
| | CASH | CREDIT 20,000 | +20,000 | CASH −30,000 | 0 → 0 |
| Transfer ₹100 A → B | A | DEBIT 10,000 | −10,000 | A 20,000 | |
| | B | CREDIT 10,000 | +10,000 | B 10,000 | 0 → 0 |

Final: A 20,000 + B 10,000 + CASH −30,000 = 0.

Allowed shapes (enforced at commit): DEPOSIT = SYSTEM debit, CUSTOMER credit; WITHDRAWAL = CUSTOMER debit,
SYSTEM credit; TRANSFER = CUSTOMER debit, CUSTOMER credit.

**Money format:** amounts are JSON **strings** of rupees with at most 2 decimals (`"1500.50"`), converted exactly
to paise. A JSON number (`1500.5`), zero, a negative, more than 2 decimals or an exponent is 400. There is no
floating point anywhere.

### Money limits

| Rule | Value | Where enforced |
|---|---|---|
| Maximum single operation | ₹10,00,000.00 (100,000,000 paise) | posting function (refusal `AMOUNT_LIMIT_EXCEEDED`) and a check constraint |
| Maximum customer balance | ₹1,00,00,000.00 (1,000,000,000 paise) | posting function (refusal `BALANCE_LIMIT_EXCEEDED`, or `DESTINATION_UNAVAILABLE` for a transfer recipient) and a check constraint |
| No overdraft | balance ≥ 0 | posting function (refusal `INSUFFICIENT_FUNDS`) and a check constraint |
| Settlement account | balance ≤ 0 | check constraint |
| Currency | INR only | check constraints |

### API

| Method and path | No token | ACCOUNT_HOLDER | ADMIN | AUDITOR |
|---|---|---|---|---|
| `POST /api/accounts/{id}/deposits` `{"amount","description"?}` + `Idempotency-Key` | 401 | own account (else 404) | 403 | 403 |
| `POST /api/accounts/{id}/withdrawals` (same body) | 401 | own account (else 404) | 403 | 403 |
| `POST /api/accounts/{id}/transfers` `{"toAccountId","amount","description"?}` + `Idempotency-Key` | 401 | from own account to an active customer account | 403 | 403 |
| `GET /api/accounts/{id}/statement?page=&size=` | 401 | own account (else 404) | 403 | 403 |
| `GET /api/oversight/accounts/{id}/statement?page=&size=` | 401 | 403 | any account, incl. settlement | any account, incl. settlement |
| `GET /api/oversight/reconciliation` | 401 | 403 | 200 | 200 |

Response codes for money requests:

| Code | Meaning |
|---|---|
| 201 | Posted. Body: `outcome: POSTED`, `transactionId`, `amount`, `balance` (the caller's account after), `sequence`, `initiatedBy`, `counterpartyAccountId`. |
| 422 | Refused and **recorded on the caller's statement**. Body: `outcome: REFUSED`, `reason` (`INSUFFICIENT_FUNDS`, `ACCOUNT_NOT_ACTIVE`, `DESTINATION_UNAVAILABLE`, `AMOUNT_LIMIT_EXCEEDED`, `BALANCE_LIMIT_EXCEEDED`), unchanged `balance`. No journal transaction is created. |
| 409 | The `Idempotency-Key` was already used for a different request. Nothing posted or recorded. |
| 503 + `Retry-After: 1` | The account locks could not be obtained in 3 s. Nothing committed, not even the key: retry with the same key. |
| 400 | Invalid body, amount, key (1–100 printable ASCII characters, required), a transfer to the same account, or any extra field (e.g. `initiatorId`). |
| 404 | Not your account; identical to an account that does not exist. |
| 500 | Unexpected server or database error; the response never contains SQL, constraint names or values. |

A replay of the same key returns the original status and body, plus `Idempotent-Replayed: true`.

```bash
curl -X POST localhost:8080/api/accounts/<id>/deposits -H "Authorization: Bearer <token>" \
  -H 'Content-Type: application/json' -H 'Idempotency-Key: 8f6c0e1a-deposit-1' -d '{"amount":"500.00"}'
curl -X POST localhost:8080/api/accounts/<id>/transfers -H "Authorization: Bearer <token>" \
  -H 'Content-Type: application/json' -H 'Idempotency-Key: 8f6c0e1a-transfer-1' \
  -d '{"toAccountId":"<other id>","amount":"100.00","description":"rent"}'
curl localhost:8080/api/accounts/<id>/statement -H "Authorization: Bearer <token>"
```

**Statements** list every posted DEBIT and CREDIT and every refused attempt of the account, in its own sequence
order (1, 2, 3, … with no gaps), each with the **running balance** (stored at posting time under the row lock; a
refused line repeats the unchanged balance), the **initiator** (user id), the other account, the operation, the
amount, the reason for refusals, the description and the time. The response also contains the account and its
current balance. The initiator of a transfer received is the sender's user id; no names or emails are shown.

**Reconciliation** recomputes everything from the raw rows and reports each difference: account balance vs.
credits − debits, every running balance, journal debits vs. credits, sequence numbers vs. line counts, entries or
refusals without a statement line, and the total of all balances (must be 0). `consistent: true` means none.

### How posting works

All money moves through three PostgreSQL functions, `ledger_deposit`, `ledger_withdraw` and `ledger_transfer`
(V5). Each HTTP request is **one function call in one database transaction**, which contains the idempotency key,
the locks, the outcome and the balance changes:

1. Check the input (again; the API already did) and the initiator: an existing, enabled ACCOUNT_HOLDER.
2. Insert the idempotency key (`ON CONFLICT DO NOTHING`, scoped to the user). If it already exists: a different
   request is 409; the same request returns the stored outcome without taking any account lock.
3. Lock the affected accounts with `SELECT … FOR UPDATE` **in ascending id order** (the customer account and the
   settlement account, or both transfer accounts), so opposite transfers cannot deadlock. Lock timeout 3 s.
4. Under the locks: the initiator must own the account being debited or deposited into, and it must be an active
   customer account; then the business rules decide.
5. Refused: one refused attempt and one statement line on the caller's account; its balance does not change.
   Posted: one journal transaction, two ledger entries with their balances after, two statement lines and the two
   balance updates.
6. All invariant checks run before the function returns, and the transaction commits.

Details added by V6:
- The initiator's `users` row is read `FOR SHARE`. A concurrent role change or disable (`set-user-role.sql` uses
  `FOR UPDATE`) either commits first, and the posting then sees the new role, or waits until the posting commits.
- Each line's time (`posted_at`, `attempted_at`) is `clock_timestamp()` taken **after** the account locks, so on
  every account the times never decrease as the sequence number increases. (With `now()`, a request that waited
  for a lock would have shown a time earlier than the line before it.) Statements are still ordered by sequence;
  a backwards jump of the server clock could still show a later line with an earlier time.
- Session settings: the caller's `lock_timeout` is restored before returning; the function only switches its own
  ledger constraint triggers to immediate and back to deferred. `idle_in_transaction_session_timeout` is set to
  30 s for the rest of the caller's transaction, so a transaction that posts and then sits idle is ended and its
  locks released.
- An empty description is rejected (the API turns a blank description into "none"), so "none" and `""` can never
  share an idempotency fingerprint.

**Concurrency and idempotency behaviour:**

| Situation | Result |
|---|---|
| Many withdrawals at once on one account | Serialised by the account row lock; each checks funds after taking it. No overdraft. |
| Identical requests (same user and key) at once | The second waits on the first's uncommitted key; once it commits, it returns the stored result. Exactly one posting. |
| Same key, different request | 409, even while the first is still running (after waiting for it). |
| The first request rolls back (e.g. lock timeout) | Its key disappears; a waiting duplicate then posts normally. |
| A refused request retried with the same key | The original refusal is returned, even if funds have arrived. Use a new key to try again. |
| A→B and B→A at once | Both lock the lower id first: no deadlock. |
| Lock timeout or deadlock | 503; everything rolled back, including the key; retrying with the same key is safe. |

### Invariants enforced by PostgreSQL

Deferred constraint triggers check the **final state at commit**; any failure rolls back the whole transaction:

- **Journal:** exactly one DEBIT and one CREDIT, both equal to the journal amount, two different accounts, account
  kinds matching the operation (a journal with no entries or one entry cannot commit).
- **Balance chain:** each entry's balance after = the account's previous posted balance ± the amount (refused lines
  are skipped), and the latest entry equals `accounts.balance_minor`. A balance changed without an entry fails at
  commit. Hence balance = credits − debits for every account, including the settlement account.
- **Sequence:** every posted or refused line has exactly one statement line; numbers per account are 1..n with no
  gaps (`account_statement_lines` primary key plus an immediate gap check); `last_seq` = the highest number. The
  counter is a column updated under the row lock, not a PostgreSQL sequence, so a rollback leaves no gap.
- **Idempotency:** every key has exactly one outcome (a journal transaction or a refused attempt), made by the
  key's user for the key's operation (V6).
- **Ownership:** a journal's customer side, and every refused attempt, is on a CUSTOMER account owned by its
  initiator (V6).
- **One system account:** only the fixed settlement account can be `SYSTEM` (check plus unique index, V6).
- **Append-only:** `UPDATE`, `DELETE` and `TRUNCATE` on the five ledger tables are rejected by triggers, even for
  the owner. An account's id, owner, kind and creation time cannot change.

### Database permissions (ledger)

| Object | `ledgerline_app` | PUBLIC |
|---|---|---|
| `accounts` | `SELECT`; `INSERT (id, owner_id, name)`; no `UPDATE` (so no balance change) | none |
| `journal_transactions`, `ledger_entries`, `refused_attempts`, `account_statement_lines` | `SELECT` only | none |
| `idempotency_keys` | none | none |
| `ledger_deposit`, `ledger_withdraw`, `ledger_transfer` | `EXECUTE` | none |
| `ledger_reconciliation` (runs with the caller's rights, read-only) | `EXECUTE` | none |
| Every other ledger function (internal checks, triggers, `ledger_post`, `ledger_outcome`) | none | none |

The posting functions are `SECURITY DEFINER`, owned by the migration role, with `search_path = pg_catalog,
pg_temp`, fully qualified table names and no dynamic SQL. `LedgerSchemaIT` checks all of this from the catalog.

**Trust boundary.** The posting functions check the initiator id they are **given**. All requests share one
database role, so PostgreSQL cannot know which HTTP user is calling. The application passes only the
authenticated user's id (`CurrentUser`; there is no request field for it). Someone with the `ledgerline_app`
credentials, or able to run SQL through the application, could post as any account holder; they still could not
break conservation, overdraw, exceed limits, or change or delete ledger rows, and every posting records its
initiator. See THREATS.md.

### Authorization

The backend is the only authority; the frontend's view of a role is never trusted. Two independent layers:

1. **URL rules, default deny** (`SecurityConfig`). Every endpoint is listed with its HTTP method and the roles
   allowed. Anything not listed is refused for everyone: 403 with a valid token, 401 without one. So an unknown
   path with a token is now 403, not 404.
2. **Method security.** Every public method of `AccountService` (ACCOUNT_HOLDER) and `AccountOversightService`
   (ADMIN or AUDITOR) has `@PreAuthorize`, so a service stays protected even if a future endpoint reached it
   without a URL rule. A refusal there is a 403 (tested), never the login 401.

Roles and the enabled flag are read from PostgreSQL on every request, so a role change or a disable applies to
the next request made with an existing token. Role claims inside a token are ignored, even in a correctly signed
one. ADMIN and AUDITOR are separate roles with no hierarchy: each grant is listed explicitly.

**Ownership and IDOR protection:**
- The owner of a new account is always the authenticated user, read from the security context in the service
  (`CurrentUser`). No request, path or token claim can name an owner.
- Every customer read uses an owner-scoped query (`findByIdAndOwnerId`, `findByOwnerId`). The customer service has
  no way to load an account without the caller's id: `AccountRepository` exposes only the methods listed in it,
  and the unscoped ones are used only by the oversight service.
- Another user's account and an account that does not exist give the **same 404**: same status, title and
  detail; only `instance`, the path the caller asked for, differs. So ids cannot be probed. Account ids are random UUIDs, but nothing relies on that: guessing an id gives nothing.
- PostgreSQL refuses any change of an account's owner or status by the application (see Database setup).

### How authentication works

- **Passwords:** 12 to 72 bytes in UTF-8 (BCrypt reads at most 72 bytes). Stored only as a BCrypt hash with cost
  factor 12. Responses have no password or hash field.
- **Emails:** trimmed and lower-cased before validation and storage; printable ASCII only, so Java's and
  PostgreSQL's lower-casing always agree. Uniqueness is enforced by the database (`uq_users_email`), which also
  settles two simultaneous registrations of the same email.
- **Login:** an unknown email, a wrong password and a disabled account all return the same 401. Spring's
  `DaoAuthenticationProvider` runs a dummy BCrypt check for unknown emails, and LedgerLine checks the disabled
  flag only after the password, so all three take the same slow path.
- **Tokens:** HS256 JWTs via Spring Security's OAuth2 resource server (Nimbus). Claims: `iss`, `sub` (the user's
  UUID), `iat`, `exp` (15 minutes), `jti`. **No role claim.** Only HS256 tokens with our signature, issuer,
  subject and expiry are accepted (`alg: none` is rejected). Expiry allows Spring's default 60 seconds of clock
  skew.
- **Every authenticated request reloads the user from PostgreSQL** by the token's subject. A deleted or disabled
  user is refused immediately, and the role always comes from the database, so a role change applies on the next
  request.
- **Stateless:** no sessions or cookies; CSRF protection is off because nothing is sent automatically by the
  browser. CORS allows only the configured origins.
- **Rate limiting:** see the next section.

### Rate limiting

Both limiters share one counting core, `AttemptLimiter`. It keeps, per key, a window start and a count, and does
every check and update under a single lock. A request **reserves** its attempt atomically: if any of its counters
is already at its maximum, nothing is counted and the request gets 429 with `Retry-After`; otherwise every one of
its counters is incremented. Because the check and the increment are one step, parallel requests cannot get more
attempts through than the limit, however many arrive at once. A refused request is not counted, so 429s do not
extend a lockout. Windows are fixed, start at the first counted attempt, and end `window` later.

**A reservation is never partly counted.** Each limiter holds at most `max-tracked-keys` keys. In order:
1. If any of the request's counters is at its maximum: 429 with that counter's remaining time. This applies even
   when the table is full.
2. Otherwise, if all of its keys are already tracked or there is room for the new ones: every counter is counted.
3. Otherwise (full, even after removing expired entries): **nothing is counted**, and the limiter's capacity
   policy decides:
   - **Login: refused** (429, `Retry-After: 60`), before the password is checked. Letting it through untracked
     would switch off the per-email limit, which is what stops guessing from many IPs. Filling the table takes at
     least `max-tracked-keys / 2` failed password checks in one window (two keys per attempt; 50,000 by default).
     Measured locally, one BCrypt check at cost 12 takes about 274 ms of CPU, so that is about 3.8 CPU-hours
     within 15 minutes: an attack already overloading the server. Refusing before BCrypt sheds that load.
   - **Registration: allowed and not counted** (the whole attempt). IPs already tracked stay limited. Refusing
     would let anyone who fills the table (duplicate-email attempts are counted without BCrypt, so it is cheap)
     stop all registrations, and an attacker with 100,000 addresses is not restrained by a per-IP limit anyway.

Releasing such an untracked attempt changes nothing. Removing expired entries means scanning the whole table
(about 2 ms for 100,000 entries, measured locally) while holding the lock. Both reasons to scan, routine
housekeeping every 1,000 reservations and making room when full, share one gate: each limiter scans **at most once
per second**, however many requests arrive. So expired entries free their space within about a second of the next
request that needs it. A warning is logged the first time a
limiter is full. Memory: roughly 20 MB per 100,000 keys per limiter (a rough local measurement).

**Login** (`LoginRateLimiter`, counters per normalised email and per client IP):
1. The attempt is reserved against both counters **before** the password is checked.
2. A failed login (the generic 401) keeps the reservation: it stays counted until the window ends.
3. A successful login, or one that fails for an unexpected reason such as a database error (a 500, not a 401),
   **releases its own reservation**. That removes exactly that attempt's count, and only if its window has not since been replaced.
   It never removes other attempts' counts, whether in flight or failed. **A success does not erase earlier
   failures.**
4. So each counter holds failed attempts plus attempts currently being checked, and successful logins alone are
   never limited.
- Emails are stored only as an HMAC-SHA256 under a random key created at startup. Passwords never reach the
  limiter. The 429 body does not say whether the email or the IP triggered it.

**Registration** (`RegistrationRateLimiter`, one counter per client IP):
- An attempt is reserved before the duplicate-email check, BCrypt and the insert, so a refused request does no
  database work and no hashing.
- Created accounts and duplicate-email attempts (409) stay counted.
- Only unexpected failures are released.
- Requests rejected by validation (400) never reach the limiter.

### Known limitations

- **The login rate limiter is in memory, per application instance:**
  - With several instances, each keeps its own counts, so the effective limit is multiplied by the instance
    count.
  - Counts reset when the application restarts.
  - A shared limit would need a shared store, such as PostgreSQL or Redis. (Within one instance, parallel
    requests cannot exceed a limit: see Rate limiting.)
  - The same applies to the registration limiter.
- **Targeted lockout:** anyone who knows an email can make it hit the per-email limit, so the real owner gets 429
  for up to one window (15 minutes by default). This is the usual trade-off of per-account limits.
- **Shared IP addresses:** many users behind one NAT or proxy share the per-IP limit. A misconfigured proxy (see
  Production) makes everyone share one.
- **A user who mistypes several times** has fewer attempts left for the rest of the window, even after signing in
  successfully: success does not erase earlier failures. This is deliberate, so a legitimate login cannot reset an
  attacker's count against the same email.
- **IPv6:** the per-IP counters use the full address. A client with many IPv6 addresses (for example a /64) can
  spread attempts across them; the per-email login limit still applies.
- **Capacity** (see Rate limiting): when a limiter's table is full, logins that need a new email or IP key are
  refused (429) until old windows expire, so a large enough attack can lock out users who have not signed in
  recently. Registrations from new IPs are let through uncounted in the same situation. Old entries are freed only
  when their window ends: there is no eviction of live entries. Raising `max-tracked-keys` costs memory (roughly
  20 MB per 100,000 keys).
- **No refresh tokens or logout.** A token stays valid until it expires (15 minutes), unless the user is
  disabled. The client signs in again after expiry.
- **Registration reveals whether an email is registered** (409). Login does not.
- **Frontend session:** the token is held in memory only, so reloading the page or opening a new tab requires
  signing in again. There is no refresh token and no server-side logout.
- **Frontend features follow the API:** no user directory, no admin actions and no account lookup by name. Demo
  accounts are created by `scripts/seed-demo.py` at deployment time; their credentials are never in the repository.
- **Local PostgreSQL uses `trust` authentication** (see above), so database passwords are not verified locally.
- **Accounts (Phase 4):**
  - Ownership of a new account is enforced by the application, not by PostgreSQL. The app role may insert any
    existing user's id as `owner_id`; only the Java code ensures it is the caller. Row-level security is not used.
  - "Staff cannot open accounts" is likewise enforced by the application (URL rule and `@PreAuthorize`), not by a
    database constraint.
  - There is no limit on how many accounts a holder can open.
  - The largest allowed page offset (page 10,000 × size 100) makes PostgreSQL skip up to a million rows.
  - Not implemented: freezing, closing or deleting accounts through the API (the database owner can set `status`),
    listing users, and admin actions. No API changes roles; `set-user-role.sql` remains the only way.
- **Ledger (Phase 5):**
  - The posting functions trust the initiator id the application gives them (see Ledger, "Trust boundary").
  - Every deposit and withdrawal locks the single settlement account row, so they are serialised across the whole
    system. Transfers between customers are not. Fine for a demo; a high-volume system would need a different design.
  - Idempotency keys are kept forever (no expiry). A refused key cannot be reused, even after funds arrive.
  - A transfer's destination is any active customer account id; a refusal (`DESTINATION_UNAVAILABLE`) or success
    tells the sender whether an id is an active account.
  - An account holder can fill their own statement with refused attempts (each needs a new key); there is no rate
    limit on money requests yet.
  - Statements and reconciliation use offset paging and full scans: fine for demo volumes, not for millions of rows.
  - Ledger rows can never be deleted, so the test database grows with every integration-test run.
  - Deposits and withdrawals are simulated: no real bank or payment provider is involved. **The total of simulated
    deposits is not capped**: each operation is capped and each account balance is capped, but a holder can open
    any number of accounts and deposit again over time, so the simulated cash in the system has no upper bound.
  - Several posting calls inside one database transaction (only possible with direct database access; the API uses
    one transaction per call) are each deadlock-free on their own, but their combined locks are not taken in one
    global order, so they can deadlock with each other (PostgreSQL detects it and rolls one back). The 30 s idle
    limit bounds how long such a transaction can hold locks while idle.

## Tests

```bash
cd backend
./mvnw test                          # unit tests only; no database needed
./mvnw verify -Pintegration-tests    # unit + integration tests; needs PostgreSQL, ledgerline_test_db and JWT_SECRET

cd ../frontend
npm run lint && npm test && npm run build
./e2e/run-e2e.sh                     # end-to-end, see below
```

- Integration tests are the classes ending in `IT`, run by Maven Failsafe. They use the `test` Spring profile,
  which points the datasource at `TEST_DB_URL`.
- To run a single integration test class: `./mvnw verify -Pintegration-tests -Dit.test=DatabaseRolesIT`.
- Web unit tests (`@WebMvcTest`) load the real security configuration with a random signing key generated per
  run (`RandomJwtSecret`), so no test key is stored in the repository.
- Integration tests that must act as the database owner (disabling a user, changing a role) use `OwnerJdbc`,
  which re-checks the guarded test URL before connecting.
- Ledger (Phase 5), all against real PostgreSQL:
  - `LedgerConcurrencyIT`: real parallel HTTP requests released together, with a pool of 10 database
    connections. 50 simultaneous ₹10 withdrawals against ₹100 (exactly 10 succeed, 40 refused; 5 repetitions);
    20 identical requests with one key (exactly one posting, 19 replays; 5 repetitions); one key with two different
    amounts at once (one posting, 10 conflicts); 40 + 40 opposite transfers (all succeed, no deadlock); 120 random
    mixed operations (conservation, statements, reconciliation; 3 repetitions); lock timeout rolls back the
    posting and the key; a waiting duplicate posts after the first rolls back.
  - `LedgerTriggerIT`: rows written directly as the owner: a journal with no entries, one entry, unequal sides,
    wrong amounts, a key with no or two outcomes, a broken balance chain, wrong account kinds, a balance changed
    without an entry, a statement gap; each fails and leaves nothing. A valid hand-built posting commits.
    Reconciliation detects tampering when triggers are bypassed (in a rolled-back transaction).
  - `LedgerSchemaIT`: what the runtime role can and cannot do; append-only even for the owner; function
    ownership, `search_path`, `SECURITY DEFINER` and `EXECUTE` grants; direct function calls with invalid input and
    with non-owner, disabled, ADMIN, AUDITOR and unknown initiators.
  - `LedgerApiIT`: the worked example, the role matrix, foreign accounts (same 404 as unknown, nothing recorded on
    the victim), refusals, frozen accounts, limits, replays and conflicts, injected fields, statements and
    reconciliation.
- The `ledgerline_app` role allows 20 connections (`database/setup/create-database.sql`). Spring caches test
  contexts and their pools, so `LedgerConcurrencyIT` uses its own pool of 10 and closes it after the class.
- Authorization: `AccountAuthorizationIT` checks every role (and no token) against every account endpoint over
  HTTP with real PostgreSQL, plus cross-user 404s, list isolation, injected fields, disabled users, role changes
  with existing tokens and role claims in a validly signed token. `AccountMethodSecurityTest` checks
  `@PreAuthorize` through the real Spring proxies, with method security enabled by the application's own
  `SecurityConfig`. `AccountTablePrivilegesIT` checks the `accounts` privileges with real SQL.
- `LoginRateLimitIT` and `RegistrationRateLimitIT` run with low limits in their own Spring contexts, and
  include real parallel bursts through the HTTP layer. The shared integration-test context raises the
  registration limit (`application-test.properties`), because other tests register many users from one address.
- Concurrency of the counting core is tested with up to 64 threads released together, repeated 20 times
  (`AttemptLimiterTest`). `AuthServiceRateLimitTest` holds attempts inside the password check with a latch, so
  the in-flight case is tested deterministically. Window expiry uses a controllable clock (`MutableClock`).
- Mockito is loaded as a JVM agent (`-javaagent`, configured in `pom.xml`), which removes the JDK's warning about
  Mockito attaching itself at runtime.

### Frontend tests

- `npm test` (Vitest, React Testing Library, jsdom): money parsing and INR formatting; the API client (Bearer header,
  401 ends the session except on login, 403 is reported); error mapping (`Retry-After`, field errors, retryable
  statuses); idempotency keys (same key on retry, new key when the request changes or after a definite outcome);
  the money form (validation, confirmation, posted, refused, 409, 503 retry with the same key, network retry);
  statement rendering (debit/credit, refused lines, running balance, initiator); role-based navigation and
  "Not permitted"; login (401, 429, expired session); registration (UTF-8 byte rule, 409); reconciliation views;
  the token stays out of browser storage.
- `./e2e/run-e2e.sh` (Playwright, Chromium): builds the backend jar and starts it on port 8081 against
  **`ledgerline_test_db`** (it refuses to run otherwise; `ledgerline_db` is never used), builds the frontend for that
  backend, and drives a real browser: two holders register, deposit, withdraw, get a refusal, transfer, and check both
  statements; an auditor (promoted with `set-user-role.sql` on the test database) sees read-only oversight and
  balanced books; reloading signs out. Credentials are read from `backend/.env` into the environment only. Nothing
  is reset: each run creates new users. Requires `psql` and `npx playwright install chromium` once.

### Test database guard

Integration tests must never touch `ledgerline_db`. A test-only guard (`TestDatabaseGuard`, in
`backend/src/test/java/com/ledgerline/support/`) enforces this.

**Applies to every Spring test automatically.**
- It is registered in `backend/src/test/resources/META-INF/spring.factories` as a `ContextCustomizerFactory`.
  Spring's TestContext framework applies it to every test application context, so no test class needs to import
  or extend anything.
- It checks every context that defines a `DataSource` or `Flyway` bean. That includes integration tests started
  from an IDE without the `test` profile, which would otherwise fall back to `DB_URL`.
- Contexts with no database, such as `@WebMvcTest`, are not checked.

**Runs before any connection.** The check is a `BeanFactoryPostProcessor`: it runs after all bean definitions
are known but before any bean is created. So a rejected URL stops the test before Hikari, Flyway or Hibernate
can open a connection or apply a migration.

**What it accepts.** `TestDatabaseUrl` reads the URL text only. Every one of `spring.datasource.url`, and, if
set, `spring.datasource.hikari.jdbc-url` and `spring.flyway.url`, must be:
- prefix `jdbc:postgresql://`
- exactly one host: `localhost`, `127.0.0.1` or `[::1]`, with no user information
- port `5432`, or no port, which means 5432
- database exactly `ledgerline_test_db`
- query parameters only from the allow-list `ApplicationName`, `connectTimeout`, `sslmode`. Parameters such as
  `PGDBNAME`, `PGHOST` or `service` are rejected, because the PostgreSQL driver lets them override the URL.

It rejects missing or unresolvable URLs, malformed URLs, other databases, other hosts, other ports and
multi-host URLs. As a second check after connecting, `DatabaseRolesIT` asserts that `current_database()` is
`ledgerline_test_db`.

**Limitations:**
- It only covers tests that start a Spring application context. A plain JUnit test that opens its own JDBC
  connection, for example with `DriverManager`, is not checked. Such tests must not be written, or must be
  caught in code review.
- It validates configuration text. It cannot detect a compromised database server or operating system, or
  local name resolution that has been changed, for example `/etc/hosts` mapping `localhost` elsewhere.
