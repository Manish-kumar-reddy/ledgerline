-- Customer accounts. Phase 4 stores only who owns an account and how it is labelled: no balance (balances will be
-- derived from ledger entries) and no money movement.
--
-- The application role can create an account (id, owner and name only; currency, status and created_at always
-- come from the defaults below) and read accounts. It can never change an account's owner, status or anything else,
-- and can never delete or truncate accounts. Ownership is decided by the application from the authenticated user.
CREATE TABLE accounts (
    id         uuid         NOT NULL DEFAULT gen_random_uuid(),
    owner_id   uuid         NOT NULL,
    name       varchar(100) NOT NULL,
    -- Single currency for now. Widening it later is a deliberate migration, not something a client can choose.
    currency   varchar(3)   NOT NULL DEFAULT 'INR',
    status     varchar(20)  NOT NULL DEFAULT 'ACTIVE',
    created_at timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT pk_accounts PRIMARY KEY (id),
    CONSTRAINT fk_accounts_owner FOREIGN KEY (owner_id) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT ck_accounts_name CHECK (length(btrim(name)) > 0),
    CONSTRAINT ck_accounts_currency CHECK (currency = 'INR'),
    CONSTRAINT ck_accounts_status CHECK (status IN ('ACTIVE', 'FROZEN', 'CLOSED'))
);

-- Owner-scoped listing in creation order.
CREATE INDEX ix_accounts_owner_created ON accounts (owner_id, created_at, id);

REVOKE ALL ON TABLE accounts FROM PUBLIC;

DO $$
DECLARE
    app_role CONSTANT text := '${app_role}';
BEGIN
    IF app_role !~ '^[a-z_][a-z0-9_]{0,62}$' OR NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = app_role) THEN
        RAISE EXCEPTION 'app_role placeholder is not an existing plain lower-case role name';
    END IF;
    -- %I quotes the name as an identifier. No UPDATE, DELETE, TRUNCATE, REFERENCES or TRIGGER.
    -- The foreign key is checked by PostgreSQL as the table owner, so the application role needs nothing on users
    -- beyond what V3 already grants.
    EXECUTE format('GRANT SELECT ON TABLE accounts TO %I', app_role);
    EXECUTE format('GRANT INSERT (id, owner_id, name) ON TABLE accounts TO %I', app_role);
END $$;
