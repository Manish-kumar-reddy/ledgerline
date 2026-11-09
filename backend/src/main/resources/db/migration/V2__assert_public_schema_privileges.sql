-- Verifies that V1 really took effect, and fails the migration if it did not.
-- Needed because REVOKE/GRANT by a non-owner only WARN ("no privileges could be revoked"), which would let V1
-- be recorded as successful on a database where the public schema belongs to someone else.
--
-- ${app_role} is validated in Java (MigrationRoleValidator) before Flyway runs. A placeholder is substituted as
-- raw text before PostgreSQL parses this file, so the checks below are a second line of defence only.
DO $$
DECLARE
    app_role   CONSTANT text := '${app_role}';
    schema_acl aclitem[];
BEGIN
    IF app_role !~ '^[a-z_][a-z0-9_]{0,62}$' THEN
        RAISE EXCEPTION 'app_role placeholder is not a plain lower-case role name';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = app_role) THEN
        RAISE EXCEPTION 'Application role "%" does not exist', app_role;
    END IF;
    IF app_role = current_user THEN
        RAISE EXCEPTION 'The application role must not be the migration owner';
    END IF;
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = app_role
               AND (rolsuper OR rolcreaterole OR rolcreatedb OR rolbypassrls)) THEN
        RAISE EXCEPTION 'Application role "%" has superuser, CREATEROLE, CREATEDB or BYPASSRLS', app_role;
    END IF;

    -- PUBLIC is not a role in pg_roles: in an ACL it is the grantee OID 0. A NULL ACL means "owner defaults",
    -- which acldefault() expands, so both cases are read the same way.
    SELECT COALESCE(n.nspacl, acldefault('n', n.nspowner)) INTO schema_acl
    FROM pg_namespace n WHERE n.nspname = 'public';
    IF EXISTS (SELECT 1 FROM aclexplode(schema_acl) a WHERE a.grantee = 0 AND a.privilege_type = 'CREATE') THEN
        RAISE EXCEPTION 'PUBLIC can still CREATE in schema public';
    END IF;

    IF has_schema_privilege(app_role, 'public', 'CREATE') THEN
        RAISE EXCEPTION 'Application role can CREATE in schema public';
    END IF;
    IF NOT has_schema_privilege(app_role, 'public', 'USAGE') THEN
        RAISE EXCEPTION 'Application role lacks USAGE on schema public';
    END IF;
    IF pg_has_role(app_role, (SELECT nspowner FROM pg_namespace WHERE nspname = 'public'), 'MEMBER') THEN
        RAISE EXCEPTION 'Application role is a member of the owner of schema public';
    END IF;
END $$;
