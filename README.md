# ledgerline
Secure money movement and double-entry ledger system built with Spring Boot, PostgreSQL and React

> **Status: early development.** Working so far: the PostgreSQL foundation with Flyway migrations, and
> authentication (registration, login, JWT access tokens and a protected `/api/auth/me`). No financial features
> exist yet. Features are added phase by phase, and this README describes what actually works at each stage.

## Project structure

```text
ledgerline/
├── backend/            Spring Boot API (Java 21, Maven Wrapper)
├── database/setup/     Owner-only SQL: create roles and databases; set a user's role
├── frontend/           React app (Vite)
├── PROMPTS.md          Log of AI assistance used in this project
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

**What `ledgerline_app` may do on `users`** (verified with real SQL attempts in `UserTablePrivilegesIT`):

| Allowed | Refused by PostgreSQL |
|---|---|
| `SELECT` | `UPDATE` of any column, including `role` and `enabled` |
| `INSERT` of `id`, `full_name`, `email`, `password_hash` only. `role`, `enabled` and `created_at` always take their defaults (`ACCOUNT_HOLDER`, `true`, `now()`). | `INSERT` naming `role`, `enabled` or `created_at`; `DELETE`; `TRUNCATE`; `ALTER`/`DROP` |

Check constraints also make PostgreSQL reject a password that is not a BCrypt hash, an email that is not
trimmed and lower-case, and a blank name.

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
npm run dev
```

Then open <http://localhost:5173>.

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
- **Token storage in the browser** is decided in Phase 9; the plan is memory only, not `localStorage`.
- **Local PostgreSQL uses `trust` authentication** (see above), so database passwords are not verified locally.

## Tests

```bash
cd backend
./mvnw test                          # unit tests only; no database needed
./mvnw verify -Pintegration-tests    # unit + integration tests; needs PostgreSQL, ledgerline_test_db and JWT_SECRET

cd ../frontend
npm run lint && npm run build
```

- Integration tests are the classes ending in `IT`, run by Maven Failsafe. They use the `test` Spring profile,
  which points the datasource at `TEST_DB_URL`.
- To run a single integration test class: `./mvnw verify -Pintegration-tests -Dit.test=DatabaseRolesIT`.
- Web unit tests (`@WebMvcTest`) load the real security configuration with a random signing key generated per
  run (`RandomJwtSecret`), so no test key is stored in the repository.
- Integration tests that must act as the database owner (disabling a user, changing a role) use `OwnerJdbc`,
  which re-checks the guarded test URL before connecting.
- `LoginRateLimitIT` and `RegistrationRateLimitIT` run with low limits in their own Spring contexts, and
  include real parallel bursts through the HTTP layer. The shared integration-test context raises the
  registration limit (`application-test.properties`), because other tests register many users from one address.
- Concurrency of the counting core is tested with up to 64 threads released together, repeated 20 times
  (`AttemptLimiterTest`). `AuthServiceRateLimitTest` holds attempts inside the password check with a latch, so
  the in-flight case is tested deterministically. Window expiry uses a controllable clock (`MutableClock`).
- Mockito is loaded as a JVM agent (`-javaagent`, configured in `pom.xml`), which removes the JDK's warning about
  Mockito attaching itself at runtime.

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
