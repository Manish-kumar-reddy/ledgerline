-- Sets the password of ONE LedgerLine production role on Neon, reading it from an environment variable.
--
-- Why not psql's \password: Neon rejects pre-hashed passwords ("Hashed passwords are not supported") and requires
-- plain text, which it hashes when storing. This script keeps the password out of command-line arguments, shell
-- history and files: psql reads it from the environment and sends it only inside the TLS connection.
--
-- Remaining risk: the server receives a plain-text ALTER ROLE ... PASSWORD statement. If server-side statement
-- logging captures it (log_statement = 'ddl' or 'all', or log_min_error_statement when the statement fails), the
-- password is in the provider's logs. Neon controls those settings. Rotate the password if that is a concern.
--
-- Usage (run once per role; the variable is read from the environment, never typed on the command line):
--
--   read -rs LEDGERLINE_NEW_PASSWORD && export LEDGERLINE_NEW_PASSWORD      # paste the generated password, Enter
--   psql "host=<neon-direct-host> dbname=neondb user=neondb_owner sslmode=require" -X \
--        -v role=ledgerline_app -f database/setup/set-production-role-password.sql
--   unset LEDGERLINE_NEW_PASSWORD
--
-- Generate each password separately (Neon requires at least 60 bits of entropy; this gives far more):
--   openssl rand -base64 48 | tr -d '/+=\n' | cut -c1-40
--
-- Only ledgerline_owner and ledgerline_app can be changed. NEVER run against a local database.

\set ON_ERROR_STOP on

\if :{?role}
\else
  DO $$ BEGIN RAISE EXCEPTION 'Missing -v role=ledgerline_owner|ledgerline_app. Nothing was changed.'; END $$;
\endif
SELECT :'role' IN ('ledgerline_owner', 'ledgerline_app') AS role_is_valid \gset
\if :role_is_valid
\else
  DO $$ BEGIN RAISE EXCEPTION 'role must be ledgerline_owner or ledgerline_app. Nothing was changed.'; END $$;
\endif

\set password_given `[ -n "$LEDGERLINE_NEW_PASSWORD" ] && [ ${#LEDGERLINE_NEW_PASSWORD} -ge 20 ] && echo true || echo false`
\if :password_given
\else
  DO $$ BEGIN RAISE EXCEPTION 'LEDGERLINE_NEW_PASSWORD is missing or shorter than 20 characters. Nothing was changed.'; END $$;
\endif
\getenv new_password LEDGERLINE_NEW_PASSWORD

-- Echo nothing: -X ignores psqlrc, and ECHO/ECHO_HIDDEN are forced off so the statement is never printed.
-- (Do not add psql's -L/--log-file option: it would write the statement, with the password, to that file.)
\set ECHO none
\set ECHO_HIDDEN off
-- One statement. psql quotes :"role" as an identifier and :'new_password' as a literal (no SQL injection).
-- The password reaches the server only in this statement, inside the TLS connection.
ALTER ROLE :"role" PASSWORD :'new_password';
\unset new_password
\echo 'Password updated for' :role
