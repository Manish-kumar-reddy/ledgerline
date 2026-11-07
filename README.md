# ledgerline
Secure money movement and double-entry ledger system built with Spring Boot, PostgreSQL and React

> **Status: early development.** Only the project skeleton exists so far. Features are added phase by phase,
> and this README describes what actually works at each stage.

## Project structure

```text
ledgerline/
├── backend/     Spring Boot API (Java 21, Maven Wrapper)
├── frontend/    React app (Vite)
├── PROMPTS.md   Log of AI assistance used in this project
└── README.md
```

## Prerequisites

| Tool | Version | Notes |
|---|---|---|
| Java | 21 | The backend targets Java 21 |
| Node.js | 20.19+ or 22.12+ | Required by Vite |
| PostgreSQL | 16+ | Used from Phase 2 onwards |

Maven does not need to be installed: the backend includes the Maven Wrapper (`mvnw`), which downloads the
correct Maven version into `~/.m2` on first use.

## Running the skeleton

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
cd backend && ./mvnw test
cd frontend && npm run lint && npm run build
```
