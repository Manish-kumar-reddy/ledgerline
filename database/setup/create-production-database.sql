-- Creates the PRODUCTION database and its two restricted roles on a NEW, DEDICATED Neon project.
--
--   NEVER run this against a local database. It is separate from create-database.sql (local development), which it
--   does not change. It creates no test database and changes no server settings.
--
-- Run once, connected to the project's default database as the project's default owner role (for example
-- neondb_owner), over TLS. Example (the password is prompted for or read from PGPASSWORD; never put it in the URL):
--
--   psql "host=<neon-direct-host> dbname=neondb user=neondb_owner sslmode=require" -X \
--        -f database/setup/create-production-database.sql
--
-- What it creates (same model as local development; V1-V6 depend on it):
--   ledgerline_owner  LOGIN, NOSUPERUSER NOCREATEDB NOCREATEROLE, CONNECTION LIMIT 5   - owns the database; Flyway only
--   ledgerline_app    LOGIN, NOSUPERUSER NOCREATEDB NOCREATEROLE, CONNECTION LIMIT 20  - the running application
--   ledgerline        database owned by ledgerline_owner; PUBLIC has no privileges; ledgerline_app may CONNECT
--
-- Roles MUST be created here with SQL, not in the Neon Console or API: Neon makes Console/API roles members of
-- neon_superuser (with CREATEDB, CREATEROLE and BYPASSRLS), which migration V2 refuses for the application role.
--
-- Passwords are NOT set here. Neon accepts only plain-text passwords (it rejects pre-hashed ones, so psql's
-- \password cannot be used). Set them afterwards with set-production-role-password.sql (README.md, "Deployment").
--
-- Fails, changing nothing, if any of the roles or the database already exists. Never drops or resets anything.
--
-- Recovery if this script stops part-way (CREATE DATABASE cannot run in a transaction, so a failure after the
-- CREATE ROLE statements leaves the roles behind and a re-run then stops with "already exists").
-- ONLY in a newly created, EMPTY Neon project dedicated to LedgerLine, never in a project holding anything else:
--   1. Check what exists:
--        SELECT rolname FROM pg_roles WHERE rolname IN ('ledgerline_owner', 'ledgerline_app');
--        SELECT datname FROM pg_database WHERE datname = 'ledgerline';
--   2a. If the database does NOT exist: remove only the two new roles, then run this script again:
--        DROP ROLE ledgerline_app;
--        DROP ROLE ledgerline_owner;
--   2b. If the database DOES exist (a later statement failed): do not drop anything; finish by hand:
--        REVOKE ALL ON DATABASE ledgerline FROM PUBLIC;
--        GRANT CONNECT ON DATABASE ledgerline TO ledgerline_app;
--        REVOKE ledgerline_owner FROM CURRENT_USER;   -- ignore "was not granted" if the grant is already gone
--      then re-run the two verification SELECT statements at the end of this script.
--   If in doubt, delete the whole dedicated Neon project and start again with a new one.

\set ON_ERROR_STOP on

SELECT
  EXISTS (SELECT 1 FROM pg_roles    WHERE rolname = 'ledgerline_owner') AS owner_exists,
  EXISTS (SELECT 1 FROM pg_roles    WHERE rolname = 'ledgerline_app')   AS app_exists,
  EXISTS (SELECT 1 FROM pg_database WHERE datname = 'ledgerline')       AS db_exists,
  current_setting('server_version_num')::int >= 160000                  AS pg16_or_later
\gset

\if :owner_exists
  DO $$ BEGIN RAISE EXCEPTION 'Role ledgerline_owner already exists. Nothing was changed.'; END $$;
\endif
\if :app_exists
  DO $$ BEGIN RAISE EXCEPTION 'Role ledgerline_app already exists. Nothing was changed.'; END $$;
\endif
\if :db_exists
  DO $$ BEGIN RAISE EXCEPTION 'Database ledgerline already exists. Nothing was changed.'; END $$;
\endif
\if :pg16_or_later
\else
  DO $$ BEGIN RAISE EXCEPTION 'This script expects PostgreSQL 16 or later. Nothing was changed.'; END $$;
\endif

CREATE ROLE ledgerline_owner LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS CONNECTION LIMIT 5;
CREATE ROLE ledgerline_app   LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS CONNECTION LIMIT 20;

-- CREATE DATABASE ... OWNER requires the current role to be able to act as the new owner. Grant that temporarily,
-- and take it away again straight after.
GRANT ledgerline_owner TO CURRENT_USER;
CREATE DATABASE ledgerline OWNER ledgerline_owner ENCODING 'UTF8';
REVOKE ALL ON DATABASE ledgerline FROM PUBLIC;
GRANT CONNECT ON DATABASE ledgerline TO ledgerline_app;
REVOKE ledgerline_owner FROM CURRENT_USER;

-- Show the result for the operator to check: no superuser, CREATEDB, CREATEROLE or BYPASSRLS; no role memberships.
SELECT r.rolname, r.rolsuper, r.rolcreatedb, r.rolcreaterole, r.rolbypassrls, r.rolconnlimit,
       (SELECT string_agg(g.rolname, ', ') FROM pg_auth_members m JOIN pg_roles g ON g.oid = m.roleid
         WHERE m.member = r.oid) AS member_of
  FROM pg_roles r WHERE r.rolname IN ('ledgerline_owner', 'ledgerline_app') ORDER BY r.rolname;
SELECT d.datname, pg_get_userbyid(d.datdba) AS owner, d.datacl FROM pg_database d WHERE d.datname = 'ledgerline';

\echo 'Created roles ledgerline_owner and ledgerline_app and database ledgerline. Now set both passwords (README.md, "Deployment").'
