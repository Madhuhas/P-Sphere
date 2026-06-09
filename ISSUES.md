# ISSUES.md

## Backend
- **PostgreSQL not reachable (ECONNREFUSED ::1:5432)**
  - **Description:** Backend crashes on startup because `pg` cannot connect to Postgres at `localhost:5432`.
  - **Steps to reproduce:**
    1. Run `cd core && npm run dev`
    2. Observe server startup failure with `connect ECONNREFUSED ::1:5432`.
  - **Expected vs actual:**
    - Expected: backend starts after connecting to Postgres.
    - Actual: backend crashes and nodemon waits for changes.
  - **Suggested minimal fix:** Start PostgreSQL (listening on port 5432) and ensure credentials in `core/.env` match. If Postgres is running elsewhere, set `DB_HOST`/`DB_PORT` accordingly in `core/.env`.
  - **Additional note:** `core/.env` is currently missing, so the backend started with default configuration values and failed when Postgres was unreachable.

- **Documentation mismatch for Redis env vars in `RUNNING.md`**
  - **Description:** `RUNNING.md` previously referenced `REDIS_URL`, but backend config reads `REDIS_HOST` and `REDIS_PORT`.
  - **Suggested minimal fix:** Use `REDIS_HOST` and `REDIS_PORT` in `core/.env` and in documentation.

## Web
- (none yet)

## Mobile
- (none yet)

## Vision / Worker
- (none yet)

## LLM
- (none yet)

