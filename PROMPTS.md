# AI Assistance Log

This file records how AI tools were used to build LedgerLine. It covers the prompts given, what the AI produced,
the mistakes and errors found, and how each was corrected and verified.

**Rules for this log**
- Prompts are copied or summarised from the actual conversation. Nothing is invented.
- A mistake appears here only if it actually happened.
- "Who corrected it" says whether the AI, the developer, or both made the fix.
- Test results are only recorded after the tests actually ran.

## Tools used

| Tool | Model | Used for |
|---|---|---|
| Claude Code (VS Code extension) | Claude Opus 5.5 | Planning, code generation, running builds and tests, explanations |

---

## Session 1: 2026-11-07, Phase 1 (repository inspection and project skeleton)

### Prompt 1: project brief

The developer gave the full LedgerLine challenge brief, summarised here:

> Act as my senior full-stack development mentor, Java architect, financial systems engineer and code reviewer…
> Build LedgerLine ("Money movement with a ledger you can prove") with Java 21, Spring Boot, Maven, Spring
> Security, JWT, JPA/Hibernate, PostgreSQL, Flyway, React, Vite, Axios, React Router, JUnit 5 and Mockito…
> Genuine chronological commit history; never fabricate or backdate commits; ask before pushing…
> Create PROMPTS.md immediately… Do not invent AI mistakes… Start with Phase 1 only.

**What the AI did**
- Inspected the environment: the repository, Git history, Java, Maven, Node.js, npm and PostgreSQL.
- Found the existing repo at `~/Desktop/ledgerline`. It has one commit (`0672a3f Initial commit`, 2026-11-07),
  the remote `origin` points at the private GitHub repo, and the local and remote `main` were in sync.
- Found an untracked entry named `ledgerline` in the repo root and checked it before acting. It was an empty,
  0-byte file, not a directory or a nested repository, and the AI did not touch it at first.

### Prompt 2: Phase 1 instructions

Sent while Phase 1 was in progress:

> 1. The untracked file named `ledgerline` is an empty zero-byte file. Verify this again and remove only that
> accidental file…
> 2. … Check whether Java 21 is available. If not, explain the installation command and ask for my approval
> before installing it.
> 3. Maven is not globally installed. Set up Maven Wrapper… Do not unnecessarily modify my global environment.
> 4. … Do not modify existing PostgreSQL users or databases…
> 7. Create PROMPTS.md and begin recording actual AI interactions and corrections.
> 8. Do not push anything to GitHub without my approval.

**What the AI did**
- Checked the `ledgerline` file again (a regular, untracked, 0-byte file) and removed only that file.
- Confirmed that Java 21 is not installed. `/usr/libexec/java_home -v 21` returned the Java 25 installation
  instead. Installing Java 21 is waiting for the developer's approval.
- Generated the backend with Spring Initializr (Spring Boot 4.1.2, Java 21, Spring Web), which includes the
  Maven Wrapper. Nothing was installed globally.
- Generated the frontend with `create-vite` (React template) and removed the demo content.
- Added a `/api/health` endpoint and a test for it, extended `.gitignore`, and updated the README.
- Did not touch PostgreSQL. No users or databases were created or changed.

### Verification (commands actually run on 2026-11-07)

| Command | Result |
|---|---|
| `cd backend && ./mvnw test` | 2 tests, 0 failures (context loads; `/api/health` returns UP) |
| `./mvnw spring-boot:run`, then `curl localhost:8080/api/health` | `{"service":"ledgerline-backend","status":"UP"}` |
| `javap -v` on a compiled class | Major version 65, which is Java 21 bytecode |
| `cd frontend && npm run lint` | Exit code 0, no warnings |
| `npm run build` | Succeeded |
| `npx vite`, then fetch the page | Page served with the title "LedgerLine" |

Note: the build ran on JDK 25 while compiling for Java 21 (`--release 21`). Tests have not yet run on a real
Java 21 runtime, because Java 21 is not installed yet.

### Mistakes and errors found in this session

1. **Unneeded `.gitignore` rule (AI mistake, corrected by the AI).** The AI added
   `!.mvn/wrapper/maven-wrapper.properties` with a comment about the wrapper jar. The rule did nothing, because
   that file was never ignored, and the comment was misleading. Found while reviewing the diff; both lines were
   removed. Verified with `git add --dry-run`, which still lists `backend/.mvn/wrapper/maven-wrapper.properties`.

### Corrections made by the developer

- The developer asked for the empty `ledgerline` file to be checked again before removing it, and asked that
  Java 21 be installed only with their approval (Prompt 2).

### Prompt 3: evaluation priorities and Phase 1 verification

> Please prioritize the following evaluation requirements above unnecessary UI features: 1. Real server-side
> role-based authorization. 2. Secure account ownership validation. 3. Correct double-entry accounting.
> 4. Idempotency and duplicate transaction prevention. 5. Concurrency-safe financial operations. 6. Append-only
> audit trail. 7. Proper error handling and database rollback. 8. Automated tests written and verified by us.
> 9. Early deployment with a working live link. 10. Complete README.md and PROMPTS.md… Continue with Phase 1
> verification first.

**What the AI did:** it copied only the files that would be committed into an empty temporary folder, with no
build output or caches. It then built, tested and ran them there, to prove the commit works on its own:

| Check | Result |
|---|---|
| `./mvnw clean verify` | 2 tests, 0 failures; produced `ledgerline-backend-0.0.1-SNAPSHOT.jar` |
| `java -jar` on that jar, then `GET /api/health` | `{"status":"UP","service":"ledgerline-backend"}`; an unknown path returned 404 |
| `npm ci`, `oxlint`, `npm run build` | Lint exit code 0; build succeeded |
| Pattern scan for passwords and keys | One match: `backend/mvnw` line 189, which only checks whether the optional `MVNW_PASSWORD` variable is set. No secret is stored. |

The priorities above now set the order of later phases: server-side security and the financial integrity
guarantees come before any extra UI.

### Prompt 4: approval to commit and install Java 21

> I approve both decisions. 1. Commit Phase 1 locally using the message: "Configure initial project structure"
> 2. Install Java 21 using: brew install --cask temurin@21. Keep Java 25 installed… Do not push to GitHub yet.

**Actual error encountered:** `brew install --cask temurin@21` failed (exit code 1). Homebrew downloaded the
package, but its installer must run with `sudo`, and the AI's non-interactive shell cannot enter a password:

```text
sudo: a terminal is required to read the password; ... sudo: a password is required
Error: temurin@21: Failure while executing; `/usr/bin/sudo ... /usr/sbin/installer -pkg ...OpenJDK21U-jdk_aarch64_mac_hotspot_21.0.13_9.pkg -target /` exited with 1.
```

Homebrew removed the partial install, and Java 25 is unchanged. The same command also triggered Homebrew's
automatic cleanup, which deleted old Homebrew caches and logs, and nothing else. **Status:** Java 21 is still
not installed. The developer needs to run the install command in their own terminal, where they can enter
their password.

Phase 1 was committed with verification on Java 25 compiling for Java 21 (bytecode major version 65). The
tests had not yet run on a Java 21 runtime.

### Follow-up: Java 21 installed by the developer

Java 21 was installed outside the AI session; the AI's own install attempt had failed, as described above.
The developer confirmed "Java 21.0.13 is now successfully installed alongside Java 25", and sent these commands
to run:

> export JAVA_HOME=$(/usr/libexec/java_home -v 21) / java -version / cd ~/Desktop/ledgerline/backend /
> ./mvnw clean verify

Actual results:

| Check | Result |
|---|---|
| `/usr/libexec/java_home -V` | Java 25.0.3 and Java 21.0.13 (Temurin) are both installed |
| `java -version` with `JAVA_HOME` set to 21 | `openjdk version "21.0.13" 2026-10-20 LTS` |
| `./mvnw -v` | Maven runs on Java 21.0.13 |
| `./mvnw clean verify` | `Tests run: 2, Failures: 0, Errors: 0`, BUILD SUCCESS |
| Packaged jar on Java 21, `GET /api/health` | `{"status":"UP","service":"ledgerline-backend"}` |

The developer then asked for the check to be repeated with `PATH` set as well
(`export PATH="$JAVA_HOME/bin:$PATH"`), so that the plain `java` command is Java 21 too. Results of that rerun:
`java -version` printed `Temurin-21.0.13+9 (build 21.0.13+9-LTS)`; `JAVA_HOME` was
`/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home`; javac compiled with `release 21`; and the
clean build passed with `Tests run: 2, Failures: 0, Errors: 0, Skipped: 0`, BUILD SUCCESS.

The build printed one warning: "Mockito is currently self-attaching to enable the inline-mock-maker. This will
no longer work in future releases of the JDK." This comes from the test dependencies, and the tests still pass.
It will be addressed when Mockito-based unit tests are added.

---

## Session 2: 2026-11-08, Phase 2 (PostgreSQL setup: two roles and two databases created and verified)

### Prompt 5: database setup proposal

> We are starting Phase 2 of LedgerLine: PostgreSQL database setup… I want a separate database named
> `ledgerline_db` and a dedicated application user. Before making any changes: inspect application.properties
> and pom.xml, check whether ledgerline_db or a LedgerLine user already exists, propose the SQL… Do not execute
> any database creation commands yet.

**What the AI found (read-only queries):** `ledgerline_db` did not exist and no LedgerLine role existed.
`password_encryption = scram-sha-256` and `listen_addresses = localhost`. Every rule in `pg_hba.conf` uses
`trust`, so local passwords are not checked; the AI reported this and did not change it.

### Prompt 6: approval with conditions

> I approve the proposed design with both databases… Generate a strong random password into backend/.env…
> Make the SQL script safe to rerun… Check PostgreSQL logging configuration so the password cannot be logged in
> plaintext… Show me the final script and exact commands first.

**What the AI did:** generated a 40-character random password into `backend/.env` (file mode 600, ignored by
Git, never printed). Found `log_min_error_statement = error`, which would log a failed `CREATE ROLE` statement
together with its password, and added session-only `SET` statements to the script to prevent that. Added
existence checks so the script stops if anything already exists.

### Prompt 7: developer review, which corrected four AI mistakes

> 1. Correct your security explanation: PostgreSQL table owners can disable triggers and alter tables. Do not
> claim triggers protect append-only ledger/audit records against their owner. 2. Propose a proper ownership and
> permission strategy… 3. Replace the final password-in-log verification command. Do not expand the actual
> password into grep or another process argument… 4. … A .env file is not automatically loaded by Spring Boot.
> 5. Show me the complete final SQL file, not an abbreviated version.

### Mistakes and errors found in this session

1. **Wrong security claim about triggers (AI mistake, caught by the developer).** The AI proposed one role
   that owns all tables, and claimed that triggers would block `UPDATE`/`DELETE` on ledger and audit tables
   "for every role, including the owner". That is false. A table owner can run
   `ALTER TABLE … DISABLE TRIGGER`, drop the trigger, `TRUNCATE` the table (row-level triggers do not fire on
   `TRUNCATE`), alter or drop the table, and a superuser bypasses all of this. **Correction:** separate the
   migration owner (`ledgerline_owner`) from the runtime role (`ledgerline_app`). The runtime role owns no
   tables and gets only `INSERT`/`SELECT` on ledger and audit tables. Status: the two-role setup was executed
   (Prompt 9) and verified (Prompt 10). What has been verified so far is database and schema level only, in the
   still-empty databases: `ledgerline_app` has only CONNECT on the databases and no CREATE privilege on schema
   `public`. No tables exist yet, so the table-level grants (`INSERT`/`SELECT` only on ledger and audit
   tables) are not yet implemented or tested. They will be implemented in Flyway migrations.
2. **Verification command exposed the password (AI mistake, caught by the developer).** The proposed check was
   `grep -c "$(sed … backend/.env)" logfile`. That expands the password into grep's command-line arguments,
   where other processes can read it (for example with `ps`). **Correction:** a check that reads the password
   file and the log inside a single Python process and prints only a count. Status: the check ran in Prompt 10
   and found zero occurrences of either password in the checked server log
   (`/opt/homebrew/var/log/postgresql@18.log`).
3. **Omitted how `.env` reaches Spring Boot (AI omission, caught by the developer).** The AI generated
   `backend/.env` without explaining that Spring Boot does not read `.env` files on its own. **Correction:**
   proposed `spring.config.import=optional:file:.env[.properties]`, with real environment variables taking
   precedence in production. Status: proposed in Session 2; implemented in Session 3 (Prompt 12), where
   `application.properties` gained `spring.config.import=optional:file:.env[.properties]`.
4. **Abbreviated SQL shown for review (AI mistake, caught by the developer).** The previous message shortened
   the script with "…". The complete file is now shown in full.

### Prompt 8: final corrections before execution

> 1. Update backend/.env.example to include DB_OWNER_USERNAME, DB_OWNER_PASSWORD, DB_USERNAME and DB_PASSWORD.
> 2. Confirm the Spring Boot configuration will import backend/.env correctly during local development.
> 3. Explain safe recovery if the SQL script fails after creating only some of the roles or databases. Never
> automatically drop existing objects. 4. Confirm the owner and runtime passwords are independently generated
> and stored only in the ignored backend/.env…

**What the AI did (no SQL run, PostgreSQL untouched):**
- Updated `backend/.env.example` with both roles' variables.
- Generated `DB_OWNER_PASSWORD` independently with Python's `secrets` module: 40 letters and digits, different
  from `DB_PASSWORD`. Rewrote `backend/.env` with mode 600 and printed neither password. A search inside a
  single process found neither password in any other file in the repo.
- Tested the `.env` import without a database. The existing Phase 1 jar was run on Java 21 with
  `--spring.config.import=optional:file:.env[.properties]` and `--spring.application.name=${DB_USERNAME}`.
  Actual results:
  - Started in `backend/`: the app name became `[ledgerline_app]`, so the value was read from `.env`.
  - Started in `backend/` with the OS variable `DB_USERNAME=set-by-os`: the app name became `[set-by-os]`, so
    a real environment variable overrides `.env`.
  - Started in an empty folder: `Could not resolve placeholder 'DB_USERNAME'`. With no `.env` and no
    environment variable, startup fails immediately instead of running half-configured.
  - Neither password appeared in the log of the last run. The logs of the first two runs were overwritten and
    were not checked.
- Explained safe recovery in chat (nothing was executed). `CREATE DATABASE` cannot run inside a transaction,
  so a partial failure can leave some roles or databases created. Rerunning the script would then stop at the
  first object that already exists. The recovery plan:
  - First inspect which objects exist, and their privileges, with read-only queries.
  - Then create only the missing objects, one statement at a time, after the developer reviews them.
  - Never automatically drop or recreate existing objects.

### Prompt 9: approval to run Step 1 only

> I approve executing Step 1 only: run database/setup/create-database.sql to create the two roles and two
> databases… Do not run Step 2 (rerun) yet. Do not execute any cleanup or DROP commands…

**Actual output** (both passwords passed through environment variables read from `backend/.env`):

```text
SET
SET
SET
CREATE ROLE
CREATE ROLE
CREATE DATABASE
CREATE DATABASE
REVOKE
REVOKE
GRANT
GRANT
Created roles ledgerline_owner, ledgerline_app and databases ledgerline_db, ledgerline_test_db.
psql exit code: 0
```

Verification steps 2–7 have not been run yet; they are waiting for the developer's approval.
(This was true when it was written. Prompt 10 records the later verification of steps 3–7; Step 2, rerunning
the script, has still not been run.)

### Prompt 10: approval to run verification steps 3–7

> I approve running verification steps 3–7 only… Do not rerun the creation script, execute DROP or cleanup
> commands, modify PostgreSQL configuration, or commit/push anything.

**Actual results** (all read-only queries):

| Check | Result |
|---|---|
| `\du ledgerline*` | Both roles exist. The only attributes listed are the connection limits (`ledgerline_app` 20, `ledgerline_owner` 5), so neither role is a superuser or can create databases or roles. |
| `\l ledgerline*` | Both databases are owned by `ledgerline_owner` with UTF8 encoding. Access: `ledgerline_owner=CTc`, `ledgerline_app=c` (CONNECT only); no entry for PUBLIC. |
| CONNECT privilege | `ledgerline_owner` and `ledgerline_app`: true on both databases. `merchantgpt`: false on both. |
| CREATE on schema `public` | `ledgerline_owner`: true, `ledgerline_app`: false, in both databases |
| Tables in `public` | 0 in `ledgerline_db`, 0 in `ledgerline_test_db` |
| Login as each role to `ledgerline_db` | `ledgerline_owner|ledgerline_db` and `ledgerline_app|ledgerline_db`, exit code 0 |
| Passwords in `/opt/homebrew/var/log/postgresql@18.log` (single Python process) | `DB_OWNER_PASSWORD`: 0 times. `DB_PASSWORD`: 0 times. |

**Limits, stated honestly:**
- Logins to `ledgerline_test_db` were not tested directly. Only the CONNECT privilege was checked.
- Because `pg_hba.conf` uses `trust`, the logins prove the roles can connect, but not that the passwords are
  correct.
- `ledgerline_app` lacks the TEMPORARY privilege, so it cannot create temporary tables. This is expected and
  does not affect the planned design.
- Step 2 (rerunning the script) has not been run.

---

## Session 3: 2026-11-08, Phase 2 (Spring Boot database integration)

### Prompt 11: plan the integration (no changes)

> Phase 2 database setup and verification are complete. Now plan the Spring Boot database integration…
> Add PostgreSQL JDBC, Spring Data JPA and Flyway dependencies compatible with our existing Spring Boot version…
> Configure the application datasource to use `ledgerline_app`. Configure Flyway to use `ledgerline_owner`…
> Keep `ledgerline_test_db` separate… Avoid Hibernate automatically creating or changing production tables…
> Do not edit files yet.

**What the AI did:** read the files and looked up the versions managed by the Spring Boot 4.1.2 BOM in the local
Maven cache: Hibernate 7.4.13.Final, Flyway 12.4.0, PostgreSQL JDBC 42.7.14, HikariCP 7.0.2, Spring Data
2026.0.2. The AI then proposed a plan.

### Prompt 12: approval with changes

> I approve implementing the Spring Boot database integration with these changes to your plan… 5. Do not
> activate a PostgreSQL-dependent test profile for every Maven test. Keep existing unit tests runnable
> independently. Add a separately runnable integration-test configuration for `ledgerline_test_db`, and make it
> fail if it accidentally connects to `ledgerline_db`… 7. … Do not grant UPDATE or DELETE on ledger or audit
> tables in future migrations. 8. Document that local Flyway currently uses the owner credentials at startup…

**Design correction made by the developer:** the AI had proposed activating the `test` profile for every Maven
test run through Surefire. That would have made every unit test depend on PostgreSQL. The developer required
the unit tests to stay runnable without a database. The implementation:
- `./mvnw test` runs only the unit tests.
- Integration tests (`*IT`) run only with `./mvnw verify -Pintegration-tests`, through Maven Failsafe.

**Design detail added by the AI:** a check inside a test method would run too late, because Flyway migrates at
application startup, before any test method runs. So the guard is a test-only `FlywayConfigurationCustomizer`
(`TestDatabaseGuard`). It runs before Flyway connects, and refuses any URL whose database is not
`ledgerline_test_db`.

**What changed:**
- `pom.xml`: added `spring-boot-starter-data-jpa`, `spring-boot-starter-flyway`, `flyway-database-postgresql`,
  `postgresql` (runtime) and `spring-boot-starter-data-jpa-test` (test), with no version numbers. Added an
  `integration-tests` profile that runs Failsafe with `spring.profiles.active=test`.
- `application.properties`: `.env` import; the datasource uses `DB_USERNAME`/`DB_PASSWORD`; Flyway uses
  `DB_OWNER_USERNAME`/`DB_OWNER_PASSWORD`; `ddl-auto=validate`, `open-in-view=false`, `clean-disabled=true`,
  and the `app_role` placeholder.
- `V1__restrict_public_schema.sql`: `REVOKE CREATE ON SCHEMA public FROM PUBLIC` and
  `GRANT USAGE ON SCHEMA public TO ${app_role}`. No financial tables.
- Test files:
  - `application-test.properties`: the datasource uses `TEST_DB_URL`.
  - `TestDatabaseGuard` and its unit test `TestDatabaseGuardTest`.
  - `DatabaseRolesIT`: a new integration test.
  - `LedgerlineBackendApplicationTests` was renamed to `LedgerlineBackendApplicationIT`, because once JPA is
    on the classpath, loading the full application needs a database.
- `README.md`: added database setup, configuration and test instructions.

### Actual errors encountered

1. **Wrong assertion in `DatabaseRolesIT` (AI mistake, found by the test run and fixed by the AI).** The first
   integration run failed: `Tests run: 5, Failures: 1`. The cause was in the test, not in the security
   setup. PostgreSQL did refuse `create table` for `ledgerline_app` (Spring threw `BadSqlGrammarException`),
   and a read-only query afterwards found only `flyway_schema_history`, owned by `ledgerline_owner`. But the
   AI had asserted that Spring's wrapper message contains `permission denied for schema public`, and Spring
   7's wrapper message is only `StatementCallback; bad SQL grammar [...]`. **Correction:** assert on the
   underlying `SQLException`: SQLSTATE `42501` (insufficient_privilege) and PostgreSQL's
   `permission denied for schema public` message. **Verified:** the rerun passed.

### Verification (commands actually run on 2026-11-08, Java 21.0.13)

| Check | Result |
|---|---|
| `./mvnw clean verify` (no profile), with `DB_URL` and `TEST_DB_URL` pointed at an unused port (`127.0.0.1:1`) | 6 unit tests, 0 failures, BUILD SUCCESS. Neither `HealthControllerTest` nor `TestDatabaseGuardTest` connected to any database. |
| `./mvnw verify -Pintegration-tests` (after the fix) | Unit tests: 6, 0 failures. Integration tests: 5, 0 failures (`DatabaseRolesIT` 4, `LedgerlineBackendApplicationIT` 1). BUILD SUCCESS. |
| `DatabaseRolesIT` | `current_user` = `ledgerline_app`; `current_database()` = `ledgerline_test_db`; every applied migration has `installed_by` = `ledgerline_owner`; the app role has USAGE but not CREATE on `public`; `create table` is refused with SQLSTATE 42501 |
| `./mvnw spring-boot:run` against `ledgerline_db` | Flyway: `Migrating schema "public" to version "1 - restrict public schema"` then `Successfully applied 1 migration`. `GET /api/health` returned HTTP 200 `{"service":"ledgerline-backend","status":"UP"}`. |
| `ledgerline_db`, read-only check afterwards | `flyway_schema_history`: version 1, `installed_by` = `ledgerline_owner`, success = true. `ledgerline_app`: USAGE true, CREATE false. Only table: `flyway_schema_history`, owned by `ledgerline_owner`. |
| Guard, end to end: integration tests with `TEST_DB_URL=…/ledgerline_db` for one run | All 5 integration tests errored with `Integration tests must use ledgerline_test_db, but the configured database is 'ledgerline_db'. Refusing to run migrations.` Flyway logged no connection to `ledgerline_db`, and its history was unchanged (1 row, same timestamp). This ran after V1 was already applied to `ledgerline_db`, so even a failing guard could not have changed it. |
| Passwords in the app startup log and the PostgreSQL server log (single Python process) | 0 occurrences of either password in both |

**Compatibility warnings:** Flyway and Hibernate printed no PostgreSQL 18 compatibility warning. Flyway reported
`Database: jdbc:postgresql://localhost:5432/ledgerline_test_db (PostgreSQL 18.6)`. Maven printed these
warnings while resolving dependencies:

```text
[WARNING] Could not transfer metadata /.meta/prefixes.txt from/to MavenFeed (https://red-gate.pkgs.visualstudio.com/MavenFeed/_packaging/MavenFeed/maven/v1/): HTTP Status: 401
[WARNING] Could not transfer metadata /.meta/prefixes.txt from/to flyway-community-db-support (https://maven.pkg.github.com/flyway/flyway-community-db-support): HTTP Status: 401
```

These are extra package repositories declared by Flyway's own POM files. They require authentication, so Maven
skips them and resolves everything from Maven Central. The build is not affected.

**Not verified:** password correctness, because local `pg_hba.conf` uses `trust` authentication.

---

## Session 4: 2026-11-08, Phase 2 (strengthening the integration-test database guard)

No commit exists yet for any Phase 2 work. The latest commit is still `289b21f` (Phase 1). This session's
changes are uncommitted.

### Prompt 13: review of the guard source

> Show me the complete current contents of: 1. `backend/src/test/java/com/ledgerline/support/TestDatabaseGuard.java`
> 2. `backend/src/test/java/com/ledgerline/DatabaseRolesIT.java` Do not edit anything or run commands.

**Weaknesses the AI pointed out in its own earlier code (Session 3):**
1. The guard applied only to test classes that wrote `@Import(TestDatabaseGuard.class)`. A future `*IT` that
   forgot the import would run unguarded.
2. It compared only the database name in the URL. Host, port, multi-host URLs and query parameters were not
   checked.
3. `DatabaseRolesIT` hard-coded the role names `ledgerline_app` and `ledgerline_owner`.
4. When `spring.flyway.url` was not set, the datasource URL was checked twice.
5. The `@Bean` method and the static check were both named `requireTestDatabase`.

### Prompt 14: changes requested

> Review and strengthen the Phase 2 integration-test database safety guard… 1. Make the database guard automatic
> for every integration test (`*IT`)… 2. Strengthen JDBC URL validation. Check that the database is
> `ledgerline_test_db` and that the host and port match the expected local PostgreSQL test environment. Reject
> missing, malformed, multi-host or unexpected URLs before Flyway can connect… 3. Read expected runtime and
> migration role names from Spring configuration… 4. Rename the bean method… 5. Remove the redundant URL check…

**What the AI changed (test code only; no change to main code, PostgreSQL, roles or data):**
- `TestDatabaseGuard` is now a `ContextCustomizerFactory`, registered in
  `src/test/resources/META-INF/spring.factories`. Spring's TestContext framework applies it to every test
  context. It adds a `BeanFactoryPostProcessor` that validates the URLs before any bean is created, in every
  context that defines a `DataSource` or `Flyway` bean. `@Import` was removed from both integration tests.
- New `TestDatabaseUrl` allows only:
  - the prefix `jdbc:postgresql://`
  - one host from `localhost`, `127.0.0.1` or `[::1]`
  - port 5432, or none
  - the database name `ledgerline_test_db` exactly
  - only the allow-listed query parameters (`ApplicationName`, `connectTimeout`, `sslmode`). The AI's
    reasoning: the PostgreSQL driver lets parameters such as `PGDBNAME` override the URL path.

  It checks `spring.datasource.url` (required), and `spring.datasource.hikari.jdbc-url` and
  `spring.flyway.url` if they are set. This also replaces the duplicated check.
- `DatabaseRolesIT` reads the role names from `spring.datasource.username` and `spring.flyway.user`, with no
  defaults, so a missing setting fails the test. A new test checks that the two names differ.
- New unit tests: `TestDatabaseUrlTest` (29 cases) and a rewritten `TestDatabaseGuardTest` (9 tests). The
  rewritten tests include a check that `spring.factories` registers the guard, and one that no bean was
  created when a URL is rejected.

### Issues found during verification

1. **The first combined run did not prove the guard per test class (found by the AI while checking the
   evidence).** With `TEST_DB_URL` pointed at bad URLs, both integration test classes failed. But the report
   for `LedgerlineBackendApplicationIT` said `ApplicationContext failure threshold (1) exceeded: skipping
   repeated attempt to load context`, not the guard's message. Both classes share one cached Spring test
   context, so after the first class failed, Spring refused to rebuild it for the second. **Correction:** each
   integration test class was rerun on its own (`-Dit.test=…`) against each bad URL; results are below.
2. **Mistakes in the AI's verification commands (not in the project code).** The first bad-URL loop used
   Maven's `-q` flag, which hid the guard messages. The next attempt used a regex that cut the message off at the
   first `.` (inside `spring.datasource.url`). Both were fixed by reading the Failsafe report files with a
   corrected pattern.

### Verification (commands actually run on 2026-11-08, Java 21.0.13)

| Check | Result |
|---|---|
| `./mvnw clean verify` with `DB_URL` and `TEST_DB_URL` pointed at `127.0.0.1:1` | 39 tests, 0 failures (`HealthControllerTest` 1, `TestDatabaseUrlTest` 29, `TestDatabaseGuardTest` 9). BUILD SUCCESS. |
| `./mvnw verify -Pintegration-tests` | Integration tests: 6, 0 failures (`DatabaseRolesIT` 5, `LedgerlineBackendApplicationIT` 1); unit tests: 39, 0 failures. BUILD SUCCESS. The log shows `TestDatabaseGuard: database URLs verified as the local ledgerline_test_db` twice. |

Each integration test class was run on its own with a bad `TEST_DB_URL`. In all 8 runs, the log had 0 Flyway
or Hikari connection lines:

| Bad `TEST_DB_URL` | Guard message (same for both `LedgerlineBackendApplicationIT` and `DatabaseRolesIT`) |
|---|---|
| `jdbc:postgresql://localhost:5432/ledgerline_db` | `names database 'ledgerline_db', not ledgerline_test_db` |
| `jdbc:postgresql://db.example.com:5432/ledgerline_test_db` | `points at host 'db…` (the extraction regex stopped at the `.`; the full text, `points at host 'db.example.com', not the local test server`, is asserted in `TestDatabaseUrlTest`) |
| `jdbc:postgresql://localhost:5433/ledgerline_test_db` | `uses port 5433, not 5432` |
| `jdbc:postgresql:ledgerline_test_db` | `is not a jdbc:postgresql://host:port/database URL` |

**Proof that the guard is automatic**, with nothing changed in the repository:
- A temporary copy of the backend in the AI's scratch directory got a new `NewFeatureIT`, with only
  `@SpringBootTest` and no guard import. `.env` was symlinked into the copy, not copied.
  - With the correct URL: 1 test passed, and the log showed `TestDatabaseGuard: database URLs verified…`.
  - With `TEST_DB_URL=…/ledgerline_db`: refused with `names database 'ledgerline_db'` and 0 connection lines.
  - The copy was deleted afterwards.
- An IDE-style run of `DatabaseRolesIT` through Surefire, without the `test` profile, so `DB_URL` pointed at
  `ledgerline_db`: refused with `names database 'ledgerline_db'` and 0 connection lines.
- `flyway_schema_history` in both databases had the same row count and latest timestamp before and after all of
  these runs: `ledgerline_db` 1 row, last `2026-11-08 00:34:16.568694`; `ledgerline_test_db` 1 row, last
  `2026-11-08 00:33:26.690884`.

**Known limitations, also recorded in README.md:**
- Plain JUnit tests that open their own JDBC connection, without a Spring context, are not covered.
- The guard checks configuration text only. It does not protect against a compromised database server or
  operating system, or against changed local name resolution.

### Prompt 15: documentation update

> Yes, update README.md and PROMPTS.md… Include the real commit reference only if one exists. Do not invent a
> commit hash… Clearly document the limitation that plain JUnit tests opening their own database connections
> without a Spring context are not covered… Do not change Java code…

**What the AI did:** updated the README's Tests section with a "Test database guard" subsection, and appended
this session. No Java code, configuration, database or credential was changed.

---

## Session 5: 2026-11-08, Phase 2 (password validation in the database setup script)

Commit reference: pending. Phase 2 is not committed yet; the latest commit is `289b21f`.

### Prompt 16: reject empty passwords

> Make one small security correction to database/setup/create-database.sql. The current script checks whether
> LEDGERLINE_OWNER_PASSWORD and LEDGERLINE_APP_PASSWORD are set, but it does not reject empty values… Print a
> clear error without printing either password. Exit before executing CREATE ROLE or CREATE DATABASE…

**Why the old check was insufficient:** `\getenv` copies an environment variable even when it is set but empty,
and `\if :{?var}` only tests that the psql variable exists. So an empty value would have reached
`CREATE ROLE … PASSWORD ''`, which PostgreSQL treats as "no password".

**Why a local shell check:** the script now runs
`` \set owner_password_given `[ -n "$LEDGERLINE_OWNER_PASSWORD" ] && echo true || echo false` `` (and the same
for the app password), before `\getenv`.
- Testing the value with a SQL query (for example `SELECT length(:'owner_password')`) would have sent the
  password to the server in a SQL statement.
- In the shell, `[` is a built-in command and the variable is expanded inside the shell, so the value never
  appears in any process's arguments.
- Limitation: this needs a POSIX shell (macOS or Linux). Whitespace-only passwords are not rejected.

**Exit-code problem in the AI's first version (found by the AI in its own test run):** the first version
rejected with `\echo` and `\quit`, which stopped before any `CREATE` but exited with code 0, so a script or CI
job would have seen success. **Correction:** on failure the script now runs
`DO $$ BEGIN RAISE EXCEPTION '<VARIABLE> is missing or empty. Nothing was changed.'; END $$;`. With
`ON_ERROR_STOP on`, psql stops with exit code 3. The error text names only the variable. It does reach the
PostgreSQL server log as an `ERROR` and `STATEMENT` line, without any password.

**Verification (actually run on 2026-11-08, with placeholder values only; the real passwords were not used):**

| Case | Result |
|---|---|
| Owner missing, app missing | `ERROR: LEDGERLINE_OWNER_PASSWORD is missing or empty. Nothing was changed.`, exit 3 |
| Owner empty, app set | `ERROR: LEDGERLINE_OWNER_PASSWORD is missing or empty. Nothing was changed.`, exit 3 |
| Owner set, app missing | `ERROR: LEDGERLINE_APP_PASSWORD is missing or empty. Nothing was changed.`, exit 3 |
| Owner set, app empty | `ERROR: LEDGERLINE_APP_PASSWORD is missing or empty. Nothing was changed.`, exit 3 |
| Both set: only the script's check section (up to the `\getenv` lines, no `CREATE` statements), plus a test line | `Reached past the checks. owner_password set: TRUE  app_password set: TRUE`, exit 0 |

- Database state was unchanged. For the `ledgerline` roles and databases, the md5 fingerprints of the stored
  password hashes (compared, not printed) and the database OIDs were identical before and after.
- The placeholder used in the four rejection tests (`placeholder-not-a-real-password`) appeared 0 times in
  `/opt/homebrew/var/log/postgresql@18.log`. The both-set check used different placeholders, and the log was not
  searched for those. That snippet contains no statement that sends a password value to the server.

**Broken test in the AI's harness:** the first both-set test appended its test line with the shell's `echo`,
which turned the `\e` in `\echo` into an escape character. psql received the invalid text `cho …` and the
server rejected it as SQL. The rejected statement contained no password, and its text showed `TRUE`, meaning
the checks had already passed. Rerunning with `printf` produced the passing result shown above.

**Not done:** the complete setup script was not rerun with valid passwords. That would be the Step 2 rerun,
which has not been approved. With the existing roles, it is expected to stop at "Role ledgerline_owner already
exists".

---

## Session 6: 2026-11-08, Phase 2 (final review and verification)

Commit reference: pending. Phase 2 is not committed yet; the latest commit is `289b21f`.

**Context:** the developer requested a read-only review of all Phase 2 changes (Prompt 17), then three
corrections (Prompt 18):
- In README.md, the claim that the application can "never change the schema" now says what the runtime role
  cannot do, and a note explains that local `pg_hba.conf` uses `trust` authentication.
- The stale "not implemented yet" status in Session 2 was updated.
- `spring.datasource.hikari.maximum-pool-size=3` was added to `application-test.properties`.

### Prompt 19: verify again after the corrections

> Run the Phase 2 verification again after the latest three corrections… Confirm the integration tests use
> `ledgerline_test_db`… Confirm the test datasource uses `maximum-pool-size=3`… Do not modify any files.

**Results** of `./mvnw verify -Pintegration-tests`, run on Java 21.0.13 on 2026-11-08:
- **Tests:** 39 unit tests (`HealthControllerTest` 1, `TestDatabaseUrlTest` 29, `TestDatabaseGuardTest` 9) and
  6 integration tests (`DatabaseRolesIT` 5, `LedgerlineBackendApplicationIT` 1), with 0 failures, errors or
  skipped tests. **BUILD SUCCESS**, Maven exit code 0.
- **Database used:** Flyway logged `Database: jdbc:postgresql://localhost:5432/ledgerline_test_db
  (PostgreSQL 18.6)`, and the log shows `TestDatabaseGuard: database URLs verified as the local
  ledgerline_test_db`. `ledgerline_db` appeared 0 times in the build log.
- **Connection pool:** Hikari's configuration dump showed `maximumPoolSize = 3`, `minimumIdle = 3`,
  `username = "ledgerline_app"`, `jdbcUrl = …/ledgerline_test_db`, and `HikariPool-1 - Added connection` 3
  times. It was enabled for this run only through the environment variable `LOGGING_LEVEL_COM_ZAXXER_HIKARI=DEBUG`;
  no file was changed.
- **Passwords:** neither real database password appeared in the build log (0 occurrences of each).
- **Warnings:** the same two Maven metadata warnings as before (`MavenFeed` and `flyway-community-db-support`,
  `HTTP Status: 401`), with no effect on the build. Flyway and Hibernate printed no PostgreSQL 18
  compatibility warning.

**Issue in the AI's first attempt:** the first run used `LOGGING_LEVEL_COM_ZAXXER_HIKARI_HIKARICONFIG=DEBUG`.
Spring Boot lowercases logger names taken from environment variables, so this never matched the class
`com.zaxxer.hikari.HikariConfig` and no configuration was printed. The tests passed in that run as well. The
rerun used the package-level variable shown above.

**Correction of an earlier AI claim:** in the Prompt 17 review (finding B5, which was given in chat and not
recorded in this file before), the AI inferred two cached test contexts, each with a 10-connection Hikari pool,
from the guard message appearing twice. That was unsupported. This run shows one Hikari pool (`HikariPool-1`)
and no `HikariPool-2`. Why the guard message appears twice was not investigated.
