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
