\set ON_ERROR_STOP on
SET client_min_messages = warning;
SET password_encryption = 'scram-sha-256';
SELECT pg_advisory_lock(947016245);
DO $check$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM ticketflow_bootstrap.initialization WHERE version = 1) THEN
        RAISE EXCEPTION 'Expected NotificationFlow bootstrap missing';
    END IF;
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ticketflow_notification')
       <> EXISTS (SELECT 1 FROM pg_database WHERE datname = 'ticketflow_notification') THEN
        RAISE EXCEPTION 'Partial Notification provisioning; manual inspection required, nothing overwritten';
    END IF;
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ticketflow_notification'
        AND (rolsuper OR rolcreatedb OR rolcreaterole OR rolreplication OR rolbypassrls OR rolinherit OR NOT rolcanlogin)) THEN
        RAISE EXCEPTION 'Unexpected Notification role privileges';
    END IF;
    IF EXISTS (SELECT 1 FROM pg_database WHERE datname = 'ticketflow_notification'
        AND pg_get_userbyid(datdba) <> 'ticketflow_admin') THEN
        RAISE EXCEPTION 'Unexpected Notification database owner';
    END IF;
END
$check$;
SELECT NOT EXISTS (SELECT 1 FROM pg_database WHERE datname='ticketflow_notification') AS create_notification \gset
\if :create_notification
DO $role$
DECLARE secret text;
BEGIN
    secret := rtrim(pg_read_file('/run/secrets/postgres_notification_password'), E'\r\n');
    IF secret IS NULL OR secret !~ '^[a-f0-9]{64}$' THEN RAISE EXCEPTION 'Invalid Notification credential file'; END IF;
    BEGIN
        EXECUTE format('CREATE ROLE ticketflow_notification LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE '
            || 'NOINHERIT NOREPLICATION NOBYPASSRLS PASSWORD %L', secret);
    EXCEPTION WHEN OTHERS THEN
        RAISE EXCEPTION 'Cannot create Notification role; no credential is displayed';
    END;
END
$role$;
-- CREATE DATABASE is not transactional. Keep connections disabled until ACLs are restricted.
CREATE DATABASE ticketflow_notification TEMPLATE template0 ENCODING 'UTF8' ALLOW_CONNECTIONS false;
REVOKE ALL ON DATABASE ticketflow_notification FROM PUBLIC;
GRANT CONNECT, CREATE, TEMPORARY ON DATABASE ticketflow_notification TO ticketflow_notification;
ALTER DATABASE ticketflow_notification ALLOW_CONNECTIONS true;
\endif
\connect ticketflow_notification
\if :create_notification
BEGIN;
REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT USAGE, CREATE ON SCHEMA public TO ticketflow_notification;
CREATE SCHEMA ticketflow_provisioning;
REVOKE ALL ON SCHEMA ticketflow_provisioning FROM PUBLIC;
CREATE TABLE ticketflow_provisioning.completed (version integer PRIMARY KEY, created_at timestamptz NOT NULL DEFAULT now());
INSERT INTO ticketflow_provisioning.completed(version) VALUES (1);
COMMIT;
\endif
DO $verify$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM ticketflow_provisioning.completed WHERE version = 1) THEN
        RAISE EXCEPTION 'Notification provisioning marker missing; schema must not be adopted automatically';
    END IF;
    IF EXISTS (SELECT 1 FROM pg_auth_members WHERE member = (SELECT oid FROM pg_roles WHERE rolname = 'ticketflow_notification'))
       OR EXISTS (SELECT 1 FROM aclexplode((SELECT datacl FROM pg_database WHERE datname='ticketflow_notification')) WHERE grantee=0)
       OR EXISTS (SELECT 1 FROM aclexplode((SELECT nspacl FROM pg_namespace WHERE nspname='public')) WHERE grantee=0) THEN
        RAISE EXCEPTION 'Unexpected Notification ACL or role membership';
    END IF;
    IF NOT has_schema_privilege('ticketflow_notification','public','USAGE')
       OR NOT has_schema_privilege('ticketflow_notification','public','CREATE') THEN
        RAISE EXCEPTION 'Notification schema privileges missing';
    END IF;
END
$verify$;
