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

---

## Session 7: 2026-11-09, Phase 3 (authentication)

Commit reference: pending. Phase 3 is not committed yet; the latest commit is `5a1b6cf` (Phase 2).

### Prompt 20: inspect and plan (no changes)

> We are starting Phase 3: Authentication for LedgerLine… INSPECT ONLY. Do not modify, create, delete or format
> any files… Identify any compatibility concerns with the existing Spring Boot 4.1.2 setup…

**What the AI found (read-only):** no authentication code or security dependency existed. Spring Boot 4.1.2
manages Spring Security 7.1.2, and its resource-server starter brings Nimbus JOSE+JWT 10.9.1. Boot does not
manage jjwt (0 entries in its BOM), so the AI proposed Spring's own JWT support and listed 13 decisions (D1–D13)
for the developer.

**AI command error:** the first code search used `grep --include=*.java`. zsh expanded the glob before grep saw
it (`no matches found`), so the search did not run. It was rerun with quoted patterns.

### Prompt 21: decisions and corrections

The developer chose:
- the built-in bearer-token filter (no custom JWT filter)
- 15-minute tokens with no refresh tokens
- a database check of the user and role on every request
- owner-only promotion to ADMIN or AUDITOR
- BCrypt cost 12
- rejecting unknown JSON properties
- passwords of 12–72 UTF-8 bytes
- 409 for a duplicate email, with identical 401s for every login failure
- random emails in integration tests
- rate limiting documented rather than built
- in-memory token storage, later
- the Mockito agent fix

The developer also required corrections to the AI's plan:

> 1. Fix the proposed V2 migration's PUBLIC privilege verification. Inspect PostgreSQL ACLs correctly; do not
> assume PUBLIC is an ordinary role. 2. Safely quote and validate database role identifiers in migrations. Do
> not allow arbitrary SQL through Flyway placeholders…

**AI mistakes corrected by the developer:**
1. The planned V2 called `has_schema_privilege('public', …)`, treating PUBLIC like a role. V2 now reads the
   schema ACL with `aclexplode(COALESCE(nspacl, acldefault('n', nspowner)))`, where PUBLIC is grantee OID 0.
2. The planned V3 put `${app_role}` unquoted into `GRANT`. It now uses `format('%I', …)`.

**Finding reported by the AI:** a Flyway placeholder is substituted as raw text before PostgreSQL parses the
file. A value containing `'` breaks out of any SQL string, so SQL-side validation alone cannot stop injection.
`MigrationRoleValidator` therefore validates the value in Java before Flyway runs. The SQL checks remain as a
second line of defence. The developer approved this, and the lower-case role-name rule.

**SQL reviewed before it was applied.** V2 and V3 were tested inside a transaction that ended in `ROLLBACK` on
`ledgerline_test_db`. Afterwards there was still only `flyway_schema_history` at v1. Results:
- As `ledgerline_app`: inserts naming `role`, `enabled` or `created_at`, any `UPDATE`, `DELETE` and `TRUNCATE`
  were all `permission denied`, and `ALTER`/`DROP` gave `must be owner`.
- Plaintext passwords, mixed-case emails, duplicate emails and blank names were rejected by constraints.
- V2 refused each bad state: PUBLIC with CREATE, the app role with CREATE, an invalid placeholder, and a
  placeholder equal to the owner.

Errors in the AI's dry-run harness:
- The first run used zsh's `echo`, which mangled `\echo` (the same mistake as in Session 5), so nothing ran. It
  was rebuilt in Python.
- Two test cases were set up wrongly: the duplicate-email case ran after its first row had been rolled back,
  and the "V3 with an unknown role" case ran where the table already existed. Both were rerun correctly and
  produced the expected errors.

### Prompt 22: implement

> I approve the corrected V2 and V3 migrations and both additional details… Proceed with the full Phase 3
> implementation… Stop and report any failed test rather than hiding or bypassing it.

**Errors met during implementation, in order:**
1. **Expected:** after Spring Security was added, the existing `HealthControllerTest` failed (`200` expected,
   `401` received). The slice test was applying Spring Boot's default security. **Fixed** by a `@WebSecurityTest`
   annotation that loads the real security configuration with a random per-run signing key (`RandomJwtSecret`).
2. **The Mockito agent caused a new JVM warning:** `Sharing is only supported for boot loader classes because
   bootstrap classpath has been appended`. **Fixed** with `-Xshare:off` alongside `-javaagent`. Afterwards there
   were 0 agent or JVM warnings.
3. **Test bug (AI):** `everyLoginFailureGetsTheSameResponse` re-stubbed a mock with `given(mock.login(any()))`.
   That calls the already-stubbed method, which threw during setup. **Fixed** with `willThrow(...).given(mock)`.
4. **Application defect (AI design mistake):** the first integration run had 9 errors in `AuthFlowIT`:
   `Lock mode WRITE not supported for read-only entity`. The `User` entity combined `@Immutable` with
   `@Generated` columns, which Hibernate cannot do when reading back database defaults after an insert. Every
   registration failed. **Fixed** by removing `@Immutable` and making every column `updatable = false`.
   That run had already applied V2 and V3 to `ledgerline_test_db` (as intended); later runs reported it up to
   date.
5. **Invalid evidence (AI):** the first "bad JWT_SECRET" startup check used `timeout`, which does not exist on
   macOS (exit code 127). Java never started, so its "secret not in log" result proved nothing. It was rerun
   without `timeout`, and the valid results are below.

**Verification (actually run on 2026-11-09, Java 21.0.13):**

| Check | Result |
|---|---|
| `./mvnw clean test` with the database URLs pointed at an unused port | 91 unit tests, 0 failures, BUILD SUCCESS |
| `./mvnw clean verify -Pintegration-tests` | 91 unit and 28 integration tests (`DatabaseRolesIT` 5, `LedgerlineBackendApplicationIT` 1, `AuthFlowIT` 13, `UserTablePrivilegesIT` 9), 0 failures, BUILD SUCCESS |
| Flyway on `ledgerline_test_db` | V2 and V3 applied by `ledgerline_owner` (`Successfully applied 2 migrations … now at version v3`) |
| App started against `ledgerline_db` | Flyway applied V2 and V3. Real HTTP results: health 200 without a token; register 201 (`ACCOUNT_HOLDER`); duplicate in a different case 409; `"role":"ADMIN"` in the body 400; login 200 with `expiresIn` 900; token claims exactly `iss, sub, iat, exp, jti` with `alg` HS256 and a 900-second lifetime; wrong password and unknown email gave identical 401s; `/me` 200 with the token, and 401 `WWW-Authenticate: Bearer` without one |
| Disabling the user (owner `UPDATE`) | The same unexpired token then got 401 `Bearer error="invalid_token"`; login with the correct password got the same generic 401 |
| Stored password | `$2a$12$…`, 60 characters |
| CORS | A preflight from `http://localhost:5173` was allowed; one from `https://evil.example` got 403 |
| Startup with a bad `JWT_SECRET` (too short, not Base64, empty) | Exit 1 with no "Started" line. The messages name only the setting (`must decode to at least 32 bytes`, `is not valid Base64`, `is not set`). Neither the value nor a marker string was in the log. |
| Secrets in the app log and PostgreSQL server log | `JWT_SECRET`, both database passwords, the test user's password and the issued token: 0 occurrences each |
| `set-user-role.sql` on `ledgerline_test_db` | Missing email, missing role, invalid role, an injection attempt in the role and an unknown email were each refused with exit 3. Run as `ledgerline_app`: `permission denied`, role unchanged. Run as the owner: promoted to ADMIN. |
| V1 | Unchanged (sha1 `b3e2b90ebf`), with the same checksum in both databases |

**Data created during verification:** `ledgerline_test_db` holds the integration-test users, including one
promoted to ADMIN by the bootstrap test. `ledgerline_db` holds one manual-test user (`dev-…@example.test`),
which was then disabled. The application role cannot delete users; the owner can remove these deliberately.

**Remaining limitations** (also in README.md): no login rate limiting, with an in-memory per-IP and per-email
counter returning 429 proposed as the hardening step; no refresh tokens or revocation other than disabling a
user; registration reveals whether an email is registered; browser token storage is deferred to Phase 9; local
`trust` authentication.

### Prompt 23: improvements before the Phase 3 commit

> 1. Unknown JSON fields: Review the global `fail-on-unknown-properties` setting. Prefer restricting strict
> unknown-field rejection to authentication request DTOs… 2. Login rate limiting: Implement a simple in-memory
> rate limiter for login attempts, using both client IP and normalized email. Return HTTP 429… 3. Production
> database credentials: … Document a clear production configuration where migrations run separately with the
> owner account and the running application uses only `ledgerline_app`. Do not change the local database setup.

**Design correction by the developer:** the AI had made unknown-property rejection global
(`spring.jackson.deserialization.fail-on-unknown-properties=true`), which changes behaviour for every future
API. It is now per type:
- `RejectUnknownProperties` annotates `RegisterRequest` and `LoginRequest`.
- `StrictJsonModule` (a Jackson module that Spring Boot registers) adds a `DeserializationProblemHandler`. It
  throws only for annotated types and leaves every other type on Jackson's default, which ignores unknown
  properties.
- The global property was removed.

**Login rate limiter:**
- `LoginRateLimiter` keeps in-memory fixed windows that start at the first failure, keyed by client IP and by an
  HMAC-SHA256 of the normalised email (random key per process). It never receives a password.
- Defaults: 5 failures per email and 50 per IP in 15 minutes, with a cap of 100,000 tracked keys, all
  configurable under `ledgerline.login-rate-limit.*`.
- Blocked attempts get 429 with `Retry-After`, are refused before any password check, and are not counted again.
- A successful login clears that email's count but not the IP's.
- When the cap is reached, new keys are not tracked, so a full table cannot lock everyone out.

**Errors met during this round:**
1. **AI API mistake:** `StrictJsonModule` first called `context.addDeserializationProblemHandler(...)`, which is
   the Jackson 2 name. It failed to compile (`cannot find symbol`). The Jackson 3.1.4 `SetupContext` method is
   `addHandler(...)`, confirmed with `javap`.
2. **The first version of the production migration job failed:** running the same jar with
   `SPRING_MAIN_WEB_APPLICATION_TYPE=none` applied Flyway (`Schema "public" is up to date`), then exited with
   code 1:
   `Parameter 0 of method securityFilterChain … required a bean of type '…HttpSecurity' that could not be found`.
   `HttpSecurity` only exists in a web application. **Fixed** with
   `@ConditionalOnWebApplication(type = SERVLET)` on that one bean, so a running server still always has it.
   This is a small code change, made so that documented migration step works; it is disclosed here and in the
   report.

**Verification (actually run on 2026-11-09, Java 21.0.13):**

| Check | Result |
|---|---|
| `./mvnw clean test`, database URLs pointed at an unused port | 108 unit tests, 0 failures, BUILD SUCCESS |
| `./mvnw clean verify -Pintegration-tests` | 108 unit and 30 integration tests (`AuthFlowIT` 13, `LoginRateLimitIT` 2, `UserTablePrivilegesIT` 9, `DatabaseRolesIT` 5, `LedgerlineBackendApplicationIT` 1), 0 failures, BUILD SUCCESS |
| `StrictJsonModuleTest` (the application's own `JsonMapper`) | `RegisterRequest` with `role` and `LoginRequest` with `rememberMe` are rejected; an unannotated record with extra properties is accepted |
| `LoginRateLimiterTest` (11 tests, controllable clock) | Blocks at the limit with the correct remaining time; the email block applies from every IP; per-IP limit; reset when the window ends; the window starts at the first failure; success resets the email but not the IP; a new instance starts empty (restart); disabled never blocks; keys contain no email address; capacity behaviour |
| `LoginRateLimitIT` (limits 3 per email, 5 per IP) | Three wrong passwords give 401, the fourth gives 429 with `Retry-After`. The correct password, also from another IP, still gets 429. Another account from another IP gets 200. The IP limit then blocks a valid login. A success clears the email's failures. |
| `AuthControllerTest` | 429 with `Retry-After: 300` for 299.4 seconds remaining and a generic body; the connection's remote address is passed as the client IP |
| Production mode A: the jar from an empty folder, `SPRING_FLYWAY_ENABLED=false`, no owner variables in its environment | Started with 0 Flyway log lines and 0 errors; health 200, register 201, login 200, `/me` 200 |
| Production mode B: migration step, `SPRING_MAIN_WEB_APPLICATION_TYPE=none` with owner variables | Before the fix: exit 1 (above). After the fix: exit 0 on its own after 2.2 s, `Schema "public" is up to date`, no Tomcat |
| Migration step with `DB_USERNAME="x'; drop table users; --"` | Exit 1 with `Flyway placeholder app_role must be a lower-case PostgreSQL role name … Refusing to run migrations.`; Flyway did not connect; `users` still present, 3 history rows |
| Secrets | 0 occurrences in all outputs, and in all 57 changed or new files |
| Migrations | V1 sha1 `b3e2b90ebf`; V2 and V3 byte-identical to the approved drafts |

**Not done or not verified:**
- The Flyway CLI was not used or tested; the documented migration step is the application jar.
- Multi-instance behaviour of the rate limiter is described, not tested.
- `server.forward-headers-strategy` behind a real proxy was not tested.

---

## Session 8: 2026-11-09, Phase 3 (independent audit and remediation)

Commit reference: pending. Phase 3 is not committed yet; the latest commit is `5a1b6cf` (Phase 2).

### Prompt 24: independent audit (no changes)

> Perform a final independent security and code-quality review of all uncommitted Phase 3 changes… classify it
> as Critical blocker, High priority, Medium priority, Low priority, Accepted limitation… Finish with a clear
> recommendation: READY TO COMMIT or NOT READY TO COMMIT.

**Main finding (H1), an AI mistake found by the AI's own audit:**
- `AuthService.login` checked the limit, ran BCrypt, and only then recorded a failure. A burst of parallel
  requests all passed the check first.
- **Measured** with the jar on `ledgerline_test_db`: 40 parallel wrong passwords for one email with a limit of
  5 gave `{401: 40}`. Every guess had its password checked.
- The README and the `LoginRateLimiter` Javadoc had said a burst could exceed a limit "by a few". That was
  wrong: the overshoot was bounded only by server concurrency (Tomcat's 200 threads).

**Other findings:**
- M1: registration was unlimited (a BCrypt CPU and data-growth denial-of-service route).
- M2: the README overstated what Hibernate's `ddl-auto=validate` catches.
- L1–L8, low priority, including a leftover `.i.log` from a Phase 2 run.

Recommendation: NOT READY TO COMMIT.

### Prompts 25–26: remediation

> H1 — Mandatory: Fix the concurrent login rate-limit bypass. Reserve/count login attempts atomically before
> BCrypt password verification… Do not introduce a race where a successful login clears counters belonging to
> other in-flight attempts… M1 — Recommended: Add a configurable per-IP registration rate limit… M2 — Mandatory:
> Correct the README statement about Hibernate validation… L8 — Cleanup: Remove … `.i.log`.

The AI proposed the design, and the developer approved both open decisions ("approve both decisions"):
- **Strict success policy:** a success releases only its own attempt and does not erase earlier failures.
- **Registration default:** 10 attempts per IP per hour.

**What changed:**
- **New `AttemptLimiter`:**
  - per-key fixed windows, with every check and update under one lock
  - all-or-nothing reservation of several keys, which refuses without counting anything
  - release that removes only the caller's own count, and only within the same window
- **`LoginRateLimiter`** reserves the IP and email counters before BCrypt. A failure keeps the reservation; a
  success or an unexpected error releases it. The earlier behaviour, where a success cleared the email count,
  was removed: it could also reset an attacker's count.
- **New `RegistrationRateLimiter`:** per IP, reserved before `existsByEmail`, BCrypt and the insert.
  `AuthService.register` is no longer `@Transactional`, so a refused request never borrows a database
  connection; the insert stays transactional through `saveAndFlush`.
- **New `TooManyAttemptsException`** (429 with `Retry-After`) replaces `LoginRateLimitedException`.
- **Documentation:** the README's rate-limiting policy, the Hibernate validation section (M2) and the deployment
  rule were rewritten, and the Javadocs were corrected.
- **Test configuration:** `application-test.properties` raises the registration limit for the shared
  integration context only; the rate-limit test classes set their own low limits.
- `.i.log` was deleted.
- No migration was changed.

**Errors met during this round:**
1. **Repeated AI test mistake.** `AuthServiceRateLimitTest.aSuccessInFlightDoesNotEraseAConcurrentFailure`
   re-stubbed a mock with `when(mock.authenticate(...))`. That called the previous answer, which threw
   `BadCredentialsException`. This is the same mistake as in Session 7. **Fixed** with
   `doThrow(...).when(mock)`.
2. **Test design corrected by the AI before the first run:** a polling loop with `Thread.sleep` was replaced by
   a `CountDownLatch`, so the "refused while others are in flight" assertion does not depend on timing.

**Verification (actually run on 2026-11-09, Java 21.0.13):**

| Check | Result |
|---|---|
| `./mvnw clean test`, database URLs pointed at an unused port | 179 unit tests, 0 failures, BUILD SUCCESS |
| `./mvnw clean verify -Pintegration-tests` | 179 unit and 35 integration tests, 0 failures, BUILD SUCCESS |
| `AttemptLimiterTest` (66 runs, including three concurrency tests repeated 20 times each) | 64 threads released together on one key with a limit of 5: exactly 5 reserved, every repetition. 64 callers from one IP (limit 8) over 4 emails (limit 3): exactly 8 passed, no email over 3, and the IP count equals the number passed. 6 concurrent releases of 10 reservations leave exactly 4. |
| `AuthServiceRateLimitTest` | With 5 attempts held inside the password check, the other 35 were refused (429) while those 5 were in flight, and no 6th attempt entered the check. A success completing while a failure was in flight did not erase the failure. An unexpected error gave the attempt back. Registration over the limit made no database call and no BCrypt call. 30 parallel registrations with a limit of 3: exactly 3 got through. |
| `LoginRateLimitIT` (HTTP, limit 3 per email) | 40 parallel wrong passwords from 4 IPs: exactly 3 got 401 and 37 got 429, and the correct password then got 429. A success did not erase two earlier failures. 8 successful logins in a row were never limited. |
| `RegistrationRateLimitIT` (HTTP, limit 3 per IP) | 201, 409 and 201 counted, then 429 with no row created; another IP got 201. 5 invalid requests (400) were not counted. 20 parallel registrations: exactly 3 got 201, 17 got 429, and 3 rows were created. |
| H1 experiment repeated with the jar on `ledgerline_test_db` (default limits) | 40 parallel wrong passwords: `{401: 5, 429: 35}` (before the fix: `{401: 40}`). The correct password afterwards: 429. 30 parallel registrations from one IP after 1 earlier registration: `{201: 9, 429: 21}`, which is exactly 10. |

## Session 9: 2026-11-09, Phase 3 (review of the rate limiter's capacity handling)

Commit reference: pending. Phase 3 is not committed yet; the latest commit is `5a1b6cf` (Phase 2).

**Prompt (summary):** a focused review of how `AttemptLimiter` behaves when `max-tracked-keys` is reached: can a
login reservation be partly tracked, can a release then touch only one counter, does this contradict the
documented all-or-nothing guarantee, can registration limiting be bypassed when full, and are there CPU or
memory denial-of-service risks. Recommend a precise policy, add focused tests, correct Javadocs and README, run
all suites. No commit, stage, push or deploy.

**Findings:**
1. **AI mistake (Session 8 code): partial reservations.** At capacity, `tryReserve` skipped each new key with
   `continue` but still counted the keys already tracked. A login with a tracked IP and a new email counted only
   the IP; the email limit did not apply. This contradicted the Javadoc and README, which said every key is
   counted.
2. Release was consistent with what was recorded (it only released recorded slots), but the skipped key was
   never limited.
3. Registration: once full, every new IP was let through uncounted. Filling the table is cheap (409 attempts are
   counted without BCrypt; IPv6 gives many addresses).
4. CPU: when full, every request needing a new key scanned the whole table for expired entries under the lock.
   Memory is bounded by `max-tracked-keys`.

**Measurements (actually run locally on 2026-11-09, scratch program deleted afterwards):** one BCrypt check at
cost 12 took about 274 ms on one thread (8 cores); 100,000 email-style entries used roughly 20 MB (GC-based,
rough); one full scan of 100,000 entries took about 2.1 ms.

**Policy implemented (no reservation is ever partly counted):**
- A key at its limit gives `Blocked` first, even when full.
- If the new keys do not fit after removing expired entries, nothing is counted, and the caller's `OnFull` policy
  decides: login uses `REJECT` (429, `Retry-After: 60`, before BCrypt); registration uses `ALLOW_UNTRACKED`
  (the whole attempt, `Reservation.NONE`). Reasons and trade-offs are in the README.
- While full, the expired-entry scan runs at most once per second of clock time.
- Login `max-tracked-keys` must be at least 2 (an attempt needs two keys; with 1, every login would be refused).
  This was found by the AI while writing the tests.

**Tests added:** 8 in `AttemptLimiterTest` (replacing one that encoded the partial behaviour), 6 in
`LoginRateLimiterTest`, 1 in `AuthServiceRateLimitTest` (a full login table refuses before the password check).

**Verification (actually run on 2026-11-09, Java 21):**

| Check | Result |
|---|---|
| `AttemptLimiterTest`, `LoginRateLimiterTest`, `AuthServiceRateLimitTest` | 73, 16 and 6 tests, 0 failures |
| `./mvnw clean test`, database URLs pointed at an unused port | 193 unit tests, 0 failures, BUILD SUCCESS |
| `./mvnw clean verify -Pintegration-tests` | 193 unit and 35 integration tests, 0 failures, BUILD SUCCESS |

No migration was changed. Nothing was committed, staged or pushed.

## Session 10: 2026-11-09, Phase 3 (one purge throttle for every scan)

Commit reference: pending. Phase 3 is not committed yet; the latest commit is `5a1b6cf` (Phase 2).

**Prompt (summary):** the user found, in their own review, that the Session 9 one-second throttle only covered
the scan in `hasRoomFor`. The housekeeping scan every 1,000 reservations was still ungated, so high request volume
could cause more than one full scan per second. Make every scan share one time-based throttle, keep the capacity
policies unchanged, add tests, run all suites. No stage, commit, push or deploy.

**AI mistake (Session 9):** the README and Javadoc said scans happen "at most once per second while full", but
the existing housekeeping path was left ungated. Found by the user, not by the AI.

**Change:** one private method, `purgeExpiredIfDue`, is now the only caller of the scan. Both the housekeeping
trigger and `hasRoomFor` go through it; it scans at most once per `PURGE_INTERVAL` (1 second of clock time) and
resets the housekeeping counter only when it actually scans. Added by the AI: if the clock moves backwards, one
scan is allowed and the gate restarts from the new time, so a wall-clock correction cannot keep it closed.
`FULL_PURGE_INTERVAL` was renamed `PURGE_INTERVAL`. Reservation semantics and the login/registration capacity
policies are unchanged.

**Tests added to `AttemptLimiterTest`:** 2,500 distinct-key reservations within 225 ms of clock time (both scan
triggers arise) run exactly one scan, and one interval later exactly one more; expired entries stay until the
gate reopens, then free capacity and the new key is counted; a backwards clock jump allows one scan.
Checked against the old code: with the ungated housekeeping scan temporarily restored, the burst test failed
(`expected: 1 but was: 2`); the file was then restored.

**Verification (actually run on 2026-11-09, Java 21):** see the table below.

| Check | Result |
|---|---|
| `AttemptLimiterTest`, `LoginRateLimiterTest`, `AuthServiceRateLimitTest` | all passed |
| `./mvnw clean test`, database URLs pointed at an unused port | 196 unit tests, 0 failures, BUILD SUCCESS |
| `./mvnw clean verify -Pintegration-tests` | 196 unit and 35 integration tests, 0 failures, BUILD SUCCESS |

No migration was changed. Nothing was staged, committed or pushed.

## Session 11: 2026-11-09, Phase 3 (final review and last corrections)

Commit reference: pending. Phase 3 is not committed yet; the latest commit is `5a1b6cf` (Phase 2).

**Prompt (summary):** a final independent review of all Phase 3 code. Then, after the review, implement only the
approved corrections: the database-outage login issue with a regression test, README wording for login errors,
and the reverse-proxy documentation. No refactoring, no features, no migration changes.

**Review result:** one medium finding (below) and four low ones: proxy documentation (fixed here), a 12-byte
password minimum allowing short multi-byte passwords, control characters accepted in `fullName`, and no HTTP
test of a full rate-limit table (the last three are recorded as limitations, not changed).

**AI mistake (Session 8 code and test, found by the AI in this review):** `AuthService.login` treated every
`AuthenticationException` as a wrong password. Spring's `DaoAuthenticationProvider` wraps errors from the user
lookup, such as a database outage, in `InternalAuthenticationServiceException`, which is an
`AuthenticationException`. So during an outage every login was counted as a failure and answered with 401
"Invalid email or password"; after 5 attempts the account got 429 for the rest of the window. This contradicted
the approved policy, the README and the Javadoc. The existing test mocked `LoginAuthenticator` and so never saw
the wrapping. Reproduced before the fix with a temporary test using the real authenticator (deleted afterwards):
attempts 1-5 threw `InternalAuthenticationServiceException`, attempt 6 threw `TooManyAttemptsException`.

**Fix:** `AuthService.login` catches `InternalAuthenticationServiceException` before `AuthenticationException`
and rethrows its cause, so the reservation is released and the error becomes a 500 instead of a 401.
Regression test `AuthServiceRateLimitTest.aDatabaseOutageThroughTheRealAuthenticatorIsNeitherA401NorCounted`:
real `LoginAuthenticator`, failing repository, 12 attempts (limit 5) all end with the database exception and none
with 429; afterwards a wrong password is an ordinary failure. The 500 status itself was not tested over HTTP.

**Documentation:** README login row (401 only for authentication failures; 500 for unexpected errors, never
reported as invalid credentials), the login rate-limit rule, and the reverse-proxy advice (`native` with trusted
proxies, never `framework`, proxy overwrites `X-Forwarded-For`, application port reachable only from the proxy).

**Verification (actually run on 2026-11-09, Java 21):**

| Check | Result |
|---|---|
| `./mvnw clean test`, database URLs pointed at an unused port | 197 unit tests, 0 failures, BUILD SUCCESS |
| `./mvnw clean verify -Pintegration-tests` | 197 unit and 35 integration tests, 0 failures, BUILD SUCCESS |
| `git diff --check` | no output (covers tracked files only; untracked Phase 3 files were checked separately for trailing whitespace: none) |

No migration was changed. Nothing was staged, committed or pushed.
