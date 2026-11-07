-- LedgerLine local database setup: two roles and two empty databases.
--
--   ledgerline_owner  Migration owner. Owns the databases and, later, every table. Used only by Flyway.
--   ledgerline_app    Runtime role used by the running application. Owns nothing. Its table
--                     privileges are granted table by table in the Flyway migrations, not here.
--
-- Run once, as a PostgreSQL superuser, with both passwords supplied through the environment:
--
--   LEDGERLINE_OWNER_PASSWORD=... LEDGERLINE_APP_PASSWORD=... \
--     psql -h 127.0.0.1 -d postgres -X -f database/setup/create-database.sql
--
-- Safe to rerun: if either role or either database already exists, it stops before changing anything.
-- No tables are created here.

\set ON_ERROR_STOP on

-- 1. Require both passwords, rejecting missing AND empty values. They are never written into this file.
--    An empty value matters: PostgreSQL treats PASSWORD '' as "no password".
--    The test runs in a local shell started by psql (`[` is a shell built-in), so the password is not sent
--    to the server and does not appear in any process's arguments. Requires a POSIX shell (macOS/Linux).
\set owner_password_given `[ -n "$LEDGERLINE_OWNER_PASSWORD" ] && echo true || echo false`
\set app_password_given `[ -n "$LEDGERLINE_APP_PASSWORD" ] && echo true || echo false`
--    On failure, the DO block raises an error that names only the variable. Because ON_ERROR_STOP is on, psql
--    then stops with a non-zero exit code (3), so scripts and CI see the failure. (\quit alone would exit 0.)
\if :owner_password_given
\else
  DO $$ BEGIN RAISE EXCEPTION 'LEDGERLINE_OWNER_PASSWORD is missing or empty. Nothing was changed.'; END $$;
  \quit
\endif
\if :app_password_given
\else
  DO $$ BEGIN RAISE EXCEPTION 'LEDGERLINE_APP_PASSWORD is missing or empty. Nothing was changed.'; END $$;
  \quit
\endif
\getenv owner_password LEDGERLINE_OWNER_PASSWORD
\getenv app_password LEDGERLINE_APP_PASSWORD

-- 2. Refuse to touch anything that already exists.
SELECT
  EXISTS (SELECT 1 FROM pg_roles    WHERE rolname = 'ledgerline_owner')   AS owner_exists,
  EXISTS (SELECT 1 FROM pg_roles    WHERE rolname = 'ledgerline_app')     AS app_exists,
  EXISTS (SELECT 1 FROM pg_database WHERE datname = 'ledgerline_db')      AS db_exists,
  EXISTS (SELECT 1 FROM pg_database WHERE datname = 'ledgerline_test_db') AS test_db_exists
\gset
\if :owner_exists
  \echo 'Role ledgerline_owner already exists. Nothing was changed.'
  \quit
\endif
\if :app_exists
  \echo 'Role ledgerline_app already exists. Nothing was changed.'
  \quit
\endif
\if :db_exists
  \echo 'Database ledgerline_db already exists. Nothing was changed.'
  \quit
\endif
\if :test_db_exists
  \echo 'Database ledgerline_test_db already exists. Nothing was changed.'
  \quit
\endif

-- 3. Keep passwords out of the server log, for this session only. By default a failed statement
--    is logged with its full text (log_min_error_statement = error), which would include a password.
--    These settings need superuser rights and end with the session.
SET log_statement = 'none';
SET log_min_error_statement = 'panic';
SET log_min_duration_statement = -1;

-- 4. Two login roles, neither with special powers. PostgreSQL stores only SCRAM-SHA-256 hashes.
CREATE ROLE ledgerline_owner
  LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE
  CONNECTION LIMIT 5
  PASSWORD :'owner_password';

CREATE ROLE ledgerline_app
  LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE
  CONNECTION LIMIT 20
  PASSWORD :'app_password';

-- 5. The owner role owns both databases, so it (and only it, among non-superusers) can create tables.
--    In PostgreSQL 15+, the public schema of a new database belongs to the database owner and
--    other roles get USAGE but not CREATE, so ledgerline_app cannot create or alter tables.
CREATE DATABASE ledgerline_db      OWNER ledgerline_owner ENCODING 'UTF8' TEMPLATE template0;
CREATE DATABASE ledgerline_test_db OWNER ledgerline_owner ENCODING 'UTF8' TEMPLATE template0;

-- 6. Only the owner, the runtime role and superusers may connect.
REVOKE ALL ON DATABASE ledgerline_db      FROM PUBLIC;
REVOKE ALL ON DATABASE ledgerline_test_db FROM PUBLIC;
GRANT CONNECT ON DATABASE ledgerline_db      TO ledgerline_app;
GRANT CONNECT ON DATABASE ledgerline_test_db TO ledgerline_app;

\echo 'Created roles ledgerline_owner, ledgerline_app and databases ledgerline_db, ledgerline_test_db.'
