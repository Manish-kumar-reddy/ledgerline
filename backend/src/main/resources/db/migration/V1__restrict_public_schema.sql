-- Only the schema owner may create objects. The application role may use the schema but not create in it.
-- PostgreSQL 15+ already defaults to this; stating it protects databases created from older templates.
--
-- Rule for every later migration: the application role (${app_role}) gets only the privileges it needs,
-- and never UPDATE, DELETE or TRUNCATE on ledger or audit tables.
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
GRANT USAGE ON SCHEMA public TO ${app_role};
