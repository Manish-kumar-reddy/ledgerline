-- Sets the role of an EXISTING, already-registered user (for example to ADMIN or AUDITOR).
--
-- This is the only way to create privileged accounts. There is no API for it, and the application's runtime
-- role (ledgerline_app) has no UPDATE privilege on users, so it cannot change any role. Run it as the
-- migration owner (or a superuser), after the person has registered through the normal API:
--
--   psql -h 127.0.0.1 -U ledgerline_owner -d ledgerline_db -X \
--     -v email='person@example.com' -v role=ADMIN -f database/setup/set-user-role.sql
--
-- The script never handles a password. It changes nothing unless exactly one user has that email and the
-- role is valid. psql's :'variable' syntax quotes the values safely as SQL literals.

\set ON_ERROR_STOP on

\if :{?email}
\else
  DO $$ BEGIN RAISE EXCEPTION 'Missing -v email=<registered email>. Nothing was changed.'; END $$;
\endif
\if :{?role}
\else
  DO $$ BEGIN RAISE EXCEPTION 'Missing -v role=ADMIN|AUDITOR|ACCOUNT_HOLDER. Nothing was changed.'; END $$;
\endif

SELECT :'role' IN ('ADMIN', 'AUDITOR', 'ACCOUNT_HOLDER') AS role_is_valid \gset
\if :role_is_valid
\else
  DO $$ BEGIN RAISE EXCEPTION 'role must be ADMIN, AUDITOR or ACCOUNT_HOLDER. Nothing was changed.'; END $$;
\endif

SELECT count(*) = 1 AS user_exists FROM users WHERE email = lower(btrim(:'email')) \gset
\if :user_exists
\else
  DO $$ BEGIN RAISE EXCEPTION 'No registered user has that email. Nothing was changed.'; END $$;
\endif

BEGIN;
SELECT id, email, role AS role_before, enabled FROM users WHERE email = lower(btrim(:'email')) FOR UPDATE;
UPDATE users SET role = :'role' WHERE email = lower(btrim(:'email')) RETURNING id, email, role AS role_after;
COMMIT;
