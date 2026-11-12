# LedgerLine threat model

Scope: the backend as of Phase 5 (authentication, accounts, ledger). Each threat lists the controls that exist,
how they are verified, and what remains. "Verified" names real tests; nothing here is claimed without one.

## 1. Forged initiator and the shared database role

**Threat:** money is moved in someone else's name by supplying their user id as the initiator.

**Controls**
- The HTTP identity is established only by a valid HS256 JWT (our key, issuer, expiry), after which the user is
  loaded from PostgreSQL on every request (must exist and be enabled; the role comes from the database).
- The initiator passed to PostgreSQL is always `CurrentUser.require().id()`. No request type has an initiator
  field, and unknown JSON fields are rejected (400).
- The posting functions re-check, for the id they are given: enabled ACCOUNT_HOLDER, owner of the account being
  debited or deposited into, active customer account.

**Limitation (honest):** every request uses the same database role, `ledgerline_app`. PostgreSQL cannot know
which HTTP user is calling and does **not** authenticate end users; it trusts the initiator id the application
passes. Anyone holding the `ledgerline_app` credentials, or able to run SQL through the application, can post as
any account holder (for example, transfer a victim's money to their own account). Even then they cannot: break
conservation, overdraw a customer account, exceed the limits, set a balance directly, or change or delete ledger
rows; and every posting permanently records the claimed initiator.

Since V6 the initiator's user row is locked `FOR SHARE` while posting, so a role change or disable committed
during a posting is seen by it (or waits for it) rather than racing it.

**Verified:** `LedgerSchemaIT.aRoleChangeCommittedWhileAPostingWaitsIsSeenByThatPosting`, `LedgerServiceTest` (the initiator passed to SQL is the authenticated user), `LedgerApiIT`
(`initiatorId`/`initiatedBy` fields give 400), `LedgerSchemaIT` (direct calls with a non-owner, disabled, ADMIN,
AUDITOR or unknown initiator are refused with no state left behind).

## 2. Unauthorized account access (IDOR, privilege escalation)

**Controls**
- Default-deny URL rules list every endpoint with its method and roles; `@PreAuthorize` on every service method.
- Customer reads and money requests use owner-scoped queries; another user's account gives the same 404 as one
  that does not exist, and nothing is recorded on the victim's statement.
- ADMIN and AUDITOR cannot move money (403); their oversight is read-only. No API changes roles.
- Role claims in a token are ignored; roles and the enabled flag are read from the database on every request.

**Verified:** `AccountAuthorizationIT`, `LedgerApiIT` (role matrix, foreign vs unknown 404 bodies identical,
victim statement unchanged), `AccountMethodSecurityTest`, `LedgerMethodSecurityTest` (real Spring proxies).

## 3. Concurrent overdrafts

**Controls:** each posting locks the affected account rows (`FOR UPDATE`, ascending id order) and checks funds
after taking the lock; a check constraint forbids a negative customer balance.

**Verified:** `LedgerConcurrencyIT`: 50 simultaneous ₹10 withdrawals against ₹100 give exactly 10 postings and 40
refusals, final balance 0 (5 repetitions); 40 + 40 opposite transfers all succeed without deadlock; a 120-operation
random mixed workload conserves money and reconciles (3 repetitions).

## 4. Duplicate requests (retries, double clicks)

**Controls:** every money request needs an `Idempotency-Key`, scoped to the user. The key, the outcome and any
balance change commit together in one database transaction. A concurrent duplicate waits for the first and then
returns its stored result; a different request with the same key is 409; a rolled-back attempt leaves no key.

**Verified:** `LedgerConcurrencyIT` (20 identical requests at once: one posting, 19 identical replays; same key
with two amounts at once: one posting, 10 conflicts; lock timeout leaves no key; a waiting duplicate posts after
the first rolls back), `LedgerApiIT` (refused replays stay refused).

## 5. Ledger tampering

**Controls**
- The runtime role has no write privilege on any ledger table and no `UPDATE` on `accounts`; the only write path
  is the three posting functions.
- Append-only triggers reject `UPDATE`, `DELETE` and `TRUNCATE` on all ledger tables, even for the owner.
- Deferred constraint triggers reject at commit any journal that is unbalanced, one-sided, of the wrong account
  kinds, any broken balance chain, any balance change without an entry, and any key without exactly one outcome.
- Since V6 they also reject an outcome whose initiator or operation differs from its idempotency key, a journal or
  refusal on an account its initiator does not own, a refusal on a non-customer account, and any second SYSTEM
  account.
- Reconciliation independently recomputes every balance, running balance, journal and sequence from raw rows.

**Limitation:** the database owner (or a superuser) can drop or disable triggers. That requires the migration
credentials, which the running application does not use in production; reconciliation would then reveal changes
that break the books.

**Verified:** `LedgerSchemaIT`, `LedgerTriggerIT` (each invalid state fails at commit and leaves nothing; a
reconciliation run with triggers disabled, in a rolled-back transaction, detects a tampered balance).

## 6. SQL injection

**Controls:** all SQL uses bound parameters (`JdbcTemplate` placeholders, JPA); no SQL is built from input. The
posting functions use no dynamic SQL, a fixed `search_path = pg_catalog, pg_temp` and fully qualified tables.
`EXECUTE` is revoked from PUBLIC. The Flyway `app_role` placeholder is validated before migrations run.

**Residual risk:** an injection flaw anywhere in the application would run as `ledgerline_app` and so could
reach the posting functions (see 1). Its damage is bounded by the database controls in 1 and 5.

**Verified:** `LedgerSchemaIT` (function ownership, `search_path`, ACLs), `MigrationRoleValidatorTest`.

## 7. Refused-attempt abuse

**Threat:** writing fake refused attempts into another user's statement, or flooding statements.

**Controls:** a refusal is recorded only after ownership of the account is confirmed (in Java and again in the
function), and only on the caller's own account, never on a transfer's destination. Unauthorized attempts, invalid
input, authentication failures and conflicts are not recorded on any statement; foreign-account attempts and
conflicts are logged to the `ledgerline.security` log. A refusal never changes a balance.

**Limitation:** a holder can fill their **own** statement with refusals (each needs a new key). There is no rate
limit on money requests yet.

**Verified:** `LedgerApiIT` (refusals only on the sender's statement; victim statement unchanged; balances
unchanged), `LedgerConcurrencyIT` (40 refusals each show the unchanged balance).

## 8. Operational failures and recovery

- **Lock timeout or deadlock:** 503 with `Retry-After: 1`; the whole transaction, including the idempotency key,
  is rolled back, so retrying with the same key is safe (tested).
- **Database errors:** a generic 500; SQL, constraint names and values stay in the server log (tested in
  `LedgerControllerTest`). A database outage during login is a 500, not "invalid credentials".
- **Crash mid-request:** PostgreSQL rolls back the uncommitted transaction; nothing partial is visible (deferred
  triggers check the final state).
- **Detecting drift:** `GET /api/oversight/reconciliation` (ADMIN, AUDITOR).
- **Held locks:** a transaction that posts and then sits idle is ended after 30 s (V6), releasing its locks.
- **Misleading times:** statement times are taken after the locks (V6), so they follow the sequence order unless
  the server clock itself moves backwards.
- **Unbounded simulated money:** deposits are capped per operation and per account balance, not in total.
- **Not covered:** backups and point-in-time recovery, monitoring and alerting, multi-instance rate limiting
  (in-memory limiters, see README), and secret rotation procedures.

## 9. Deployment (Vercel, Render, Neon)

Prepared and checked locally; **not yet deployed**. Items marked "verify" must be confirmed on the live system.

- **Platform secrets.** `DB_PASSWORD` and `JWT_SECRET` live only in Render's environment settings; the migration
  owner's credentials never reach Render (the app runs with `SPRING_FLYWAY_ENABLED=false`, verified locally: no
  Flyway activity and no owner variables needed). The Docker image contains no secrets (`.dockerignore` excludes
  `.env`). Files on the developer's machine are outside the repository and `chmod 600`; the scripts refuse other
  permissions and never print secret values or pass them as command-line arguments. `VITE_API_BASE_URL` is public
  by design: nothing secret may use the `VITE_` prefix.
- **Production database isolation and migration credentials.** A dedicated Neon project; roles created by SQL
  (Neon makes Console/API roles members of `neon_superuser`, which V2 refuses). The migration runner refuses local
  hosts, the local database names and connections without `sslmode=require`, and requires typed confirmation.
  **Residual risk:** Neon accepts only plain-text passwords, so `set-production-role-password.sql` sends the
  password in one `ALTER ROLE … PASSWORD` statement (read from an environment variable, quoted by psql, over TLS,
  never echoed, never in a process argument). PostgreSQL records statement text when `log_statement` is `ddl` or
  `all`, and records a failing statement under `log_min_error_statement`, so the password could appear in Neon's
  server logs; Neon controls those settings. A client-side psql `-L` log file would also capture it (the
  documented command does not use `-L`). Rotate the password if exposure is suspected.
- **Migration environment isolation.** `migrate-production.sh` starts Java with `env -i`, so only `PATH`, `HOME`, the
  six validated variables and the two fixed Spring settings reach it; hostile inherited or file-supplied
  `SPRING_DATASOURCE_URL`, `SPRING_FLYWAY_URL`, `SPRING_APPLICATION_JSON`, `SPRING_CONFIG_*` or
  `JAVA_TOOL_OPTIONS` cannot point the migration at another database (tested with a fake `java`).
  `SPRING_FLYWAY_ENABLED` must be unset or exactly `true`.
- **TLS server authentication.** `sslmode=require` encrypts but does not verify the server certificate, so an
  attacker able to intercept the network path could impersonate the database. `verify-full` settings for the
  JDBC driver and psql are documented but **not yet tested** (deployment prerequisite; see README).
- **CORS origin.** `CORS_ALLOWED_ORIGINS` must be exactly the Vercel production URL (verify after deployment).
  Preview deployments are deliberately not allowed. CORS is not an authentication control: every request still
  needs a valid token.
- **CSP and browser exposure.** `vercel.json` sets a Content-Security-Policy without `unsafe-inline` or
  `unsafe-eval` and with `connect-src` limited to the app and the API origin, plus `frame-ancestors 'none'`,
  `nosniff`, `no-referrer` and HSTS. `connect-src` names the production API origin
  (`https://ledgerline-g6bg.onrender.com`). The E2E suite runs under the same policy (via `vite preview`, which
  swaps only that origin for the local backend's and refuses to start if it cannot) and fails on any CSP violation.
  The access token stays in memory only. If the backend host changes, `connect-src` must be updated with it, or the
  browser blocks all API calls (fails closed).
- **Reverse proxy and client IP.** With `SERVER_FORWARD_HEADERS_STRATEGY=native`, Tomcat trusts `X-Forwarded-For`
  only from private-network addresses. If Render's proxy reaches the app from another range, every client shares
  one per-IP limit (availability risk, not a bypass: the app is reachable only through Render's proxy, so the header
  cannot be forged directly). **Verify** after deployment with failed sign-ins from two networks.
- **Cold starts and availability.** Free instances are expected to sleep; the first request can take a minute or
  more, and in-memory rate limits reset on restart. `/api/health` does not check the database. Warm the service
  before demos; the sign-in page explains slow starts.
- **Demo-account lockout.** Five wrong passwords for a demo email lock it for 15 minutes (per instance, in memory).
  Anyone who knows a demo email can do this deliberately; the demo works around it by restarting the service or
  using another demo user. Demo credentials are shared privately, never committed.
- **Demo seeding.** `scripts/seed-demo.py` refuses HTTP redirects, so its bearer token cannot be forwarded to
  another host (tested). It never promotes an account it cannot sign in to with the supplied
  password, only promotes from `ACCOUNT_HOLDER`, matches the database row to the signed-in user, and uses a fixed
  idempotency key for the initial deposit so reruns cannot post it twice (verified locally on the test database).
