-- Users who can sign in. Role and enabled status are controlled by the database owner only:
-- the application role can insert a new user (which always gets the column defaults ACCOUNT_HOLDER / enabled)
-- and read users, and can never change role or enabled, update, delete or truncate.
CREATE TABLE users (
    id            uuid         NOT NULL DEFAULT gen_random_uuid(),
    full_name     varchar(100) NOT NULL,
    email         varchar(254) NOT NULL,
    password_hash varchar(60)  NOT NULL,
    role          varchar(20)  NOT NULL DEFAULT 'ACCOUNT_HOLDER',
    enabled       boolean      NOT NULL DEFAULT true,
    created_at    timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT pk_users PRIMARY KEY (id),
    CONSTRAINT uq_users_email UNIQUE (email),
    CONSTRAINT ck_users_full_name CHECK (length(btrim(full_name)) > 0),
    -- The application stores emails trimmed and lower-cased, which makes the UNIQUE constraint case-insensitive.
    CONSTRAINT ck_users_email_normalised CHECK (email = lower(btrim(email)) AND position('@' IN email) > 1),
    -- Only a BCrypt hash fits: the database itself refuses a plaintext password.
    CONSTRAINT ck_users_password_is_bcrypt CHECK (password_hash ~ '^\$2[aby]\$[0-9]{2}\$[./A-Za-z0-9]{53}$'),
    CONSTRAINT ck_users_role CHECK (role IN ('ADMIN', 'ACCOUNT_HOLDER', 'AUDITOR'))
);

REVOKE ALL ON TABLE users FROM PUBLIC;

DO $$
DECLARE
    app_role CONSTANT text := '${app_role}';
BEGIN
    IF app_role !~ '^[a-z_][a-z0-9_]{0,62}$' OR NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = app_role) THEN
        RAISE EXCEPTION 'app_role placeholder is not an existing plain lower-case role name';
    END IF;
    -- %I quotes the name as an identifier.
    EXECUTE format('GRANT SELECT ON TABLE users TO %I', app_role);
    EXECUTE format('GRANT INSERT (id, full_name, email, password_hash) ON TABLE users TO %I', app_role);
END $$;
