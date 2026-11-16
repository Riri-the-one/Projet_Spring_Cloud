\set ON_ERROR_STOP on
SET client_min_messages = warning;
SET password_encryption = 'scram-sha-256';
SELECT pg_advisory_lock(947016244);
DO $check$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM ticketflow_bootstrap.initialization WHERE version = 1) THEN
        RAISE EXCEPTION 'Expected TicketFlow bootstrap missing';
    END IF;
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ticketflow_ticket')
       <> EXISTS (SELECT 1 FROM pg_database WHERE datname = 'ticketflow_ticket') THEN
        RAISE EXCEPTION 'Partial Ticket provisioning; manual inspection required, nothing overwritten';
    END IF;
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ticketflow_ticket'
        AND (rolsuper OR rolcreatedb OR rolcreaterole OR rolreplication OR rolbypassrls OR rolinherit OR NOT rolcanlogin)) THEN
        RAISE EXCEPTION 'Unexpected Ticket role privileges';
    END IF;
    IF EXISTS (SELECT 1 FROM pg_database WHERE datname = 'ticketflow_ticket'
        AND pg_get_userbyid(datdba) <> 'ticketflow_admin') THEN
        RAISE EXCEPTION 'Unexpected Ticket database owner';
    END IF;
END
$check$;
SELECT NOT EXISTS (SELECT 1 FROM pg_database WHERE datname='ticketflow_ticket') AS create_ticket \gset
\if :create_ticket
DO $role$
DECLARE secret text;
BEGIN
    secret := rtrim(pg_read_file('/run/secrets/postgres_ticket_password'), E'\r\n');
    IF secret IS NULL OR secret !~ '^[a-f0-9]{64}$' THEN RAISE EXCEPTION 'Invalid Ticket credential file'; END IF;
    BEGIN
        EXECUTE format('CREATE ROLE ticketflow_ticket LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE '
            || 'NOINHERIT NOREPLICATION NOBYPASSRLS PASSWORD %L', secret);
    EXCEPTION WHEN OTHERS THEN
        RAISE EXCEPTION 'Cannot create Ticket role; no credential is displayed';
    END;
END
$role$;
-- CREATE DATABASE is not transactional. Keep connections disabled until ACLs are restricted.
CREATE DATABASE ticketflow_ticket TEMPLATE template0 ENCODING 'UTF8' ALLOW_CONNECTIONS false;
REVOKE ALL ON DATABASE ticketflow_ticket FROM PUBLIC;
GRANT CONNECT, CREATE, TEMPORARY ON DATABASE ticketflow_ticket TO ticketflow_ticket;
ALTER DATABASE ticketflow_ticket ALLOW_CONNECTIONS true;
\endif
\connect ticketflow_ticket
\if :create_ticket
BEGIN;
REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT USAGE, CREATE ON SCHEMA public TO ticketflow_ticket;
CREATE SCHEMA ticketflow_provisioning;
REVOKE ALL ON SCHEMA ticketflow_provisioning FROM PUBLIC;
CREATE TABLE ticketflow_provisioning.completed (version integer PRIMARY KEY, created_at timestamptz NOT NULL DEFAULT now());
INSERT INTO ticketflow_provisioning.completed(version) VALUES (1);
COMMIT;
\endif
DO $verify$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM ticketflow_provisioning.completed WHERE version = 1) THEN
        RAISE EXCEPTION 'Ticket provisioning marker missing; schema must not be adopted automatically';
    END IF;
    IF EXISTS (SELECT 1 FROM pg_auth_members WHERE member = (SELECT oid FROM pg_roles WHERE rolname = 'ticketflow_ticket'))
       OR EXISTS (SELECT 1 FROM aclexplode((SELECT datacl FROM pg_database WHERE datname='ticketflow_ticket')) WHERE grantee=0)
       OR EXISTS (SELECT 1 FROM aclexplode((SELECT nspacl FROM pg_namespace WHERE nspname='public')) WHERE grantee=0) THEN
        RAISE EXCEPTION 'Unexpected Ticket ACL or role membership';
    END IF;
    IF NOT has_schema_privilege('ticketflow_ticket','public','USAGE')
       OR NOT has_schema_privilege('ticketflow_ticket','public','CREATE') THEN
        RAISE EXCEPTION 'Ticket schema privileges missing';
    END IF;
END
$verify$;
