# ledgerline
Secure money movement and double-entry ledger system built with Spring Boot, PostgreSQL and React

> **Status: early development.** The project skeleton and the PostgreSQL connection with Flyway migrations
> work. No financial features exist yet. Features are added phase by phase, and this README describes what
> actually works at each stage.

## Project structure

```text
ledgerline/
├── backend/            Spring Boot API (Java 21, Maven Wrapper)
├── database/setup/     One-time SQL to create the database roles and databases
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

## Configuration

| Variable | Purpose |
|---|---|
| `DB_URL` | JDBC URL of the application database, e.g. `jdbc:postgresql://localhost:5432/ledgerline_db` |
| `DB_USERNAME`, `DB_PASSWORD` | Runtime role (`ledgerline_app`), used by the application's connection pool |
| `DB_OWNER_USERNAME`, `DB_OWNER_PASSWORD` | Migration owner (`ledgerline_owner`), used only by Flyway |
| `TEST_DB_URL` | JDBC URL of `ledgerline_test_db`, used only by integration tests |

- **Local development:** `application.properties` imports `backend/.env` with
  `spring.config.import=optional:file:.env[.properties]`. The path is relative to the folder the app starts in,
  which is `backend/` when you use `./mvnw spring-boot:run`.
- **Production:** set the same names as real environment variables. They take precedence over `.env`, and the
  file is optional, so it is not needed on a server. If a variable is missing, startup fails with
  `Could not resolve placeholder`.
- **Migration credentials:** Flyway currently runs at application startup with the owner credentials, so the
  owner password is present whenever the app runs. Production can instead set `spring.flyway.enabled=false` and
  run migrations as a separate deployment step, so the running app never holds the owner password.

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

## Tests

```bash
cd backend
./mvnw test                          # unit tests only; no database needed
./mvnw verify -Pintegration-tests    # unit + integration tests; needs PostgreSQL and ledgerline_test_db

cd ../frontend
npm run lint && npm run build
```

- Integration tests are the classes ending in `IT`, run by Maven Failsafe. They use the `test` Spring profile,
  which points the datasource at `TEST_DB_URL`.
- To run a single integration test class: `./mvnw verify -Pintegration-tests -Dit.test=DatabaseRolesIT`.

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
