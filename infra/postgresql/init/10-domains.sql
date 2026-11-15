\set ON_ERROR_STOP on

-- This file is only a bootstrap for an EMPTY dedicated cluster, never a migration.
-- Refuse pre-existing domain objects rather than overwrite accounts, credentials or data.
DO $bootstrap$
DECLARE
    domain text;
    secret text;
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname IN
            ('ticketflow_customer', 'ticketflow_event', 'ticketflow_booking'))
        OR EXISTS (SELECT 1 FROM pg_database WHERE datname IN
            ('ticketflow_customer', 'ticketflow_event', 'ticketflow_booking')) THEN
        RAISE EXCEPTION 'Domain objects already exist; bootstrap is not a migration';
    END IF;
    FOREACH domain IN ARRAY ARRAY['customer', 'event', 'booking'] LOOP
        secret := rtrim(pg_read_file('/run/secrets/postgres_' || domain || '_password'), E'\r\n');
        IF secret IS NULL OR length(secret) = 0 THEN
            RAISE EXCEPTION 'Missing domain credential';
        END IF;
        BEGIN
            EXECUTE format('CREATE ROLE %I LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE '
                || 'NOINHERIT NOREPLICATION NOBYPASSRLS PASSWORD %L', 'ticketflow_' || domain, secret);
        EXCEPTION WHEN OTHERS THEN
            -- Never echo a dynamically expanded password statement in an error.
            RAISE EXCEPTION 'Unable to initialize domain account; inspect local credential files';
        END;
    END LOOP;
END
$bootstrap$;

-- Intentionally outside a transaction: CREATE DATABASE cannot run in a transaction block.
-- Databases belong to the bootstrap administrator, NOT to the domain login.
CREATE DATABASE ticketflow_customer TEMPLATE template0 ENCODING 'UTF8';
CREATE DATABASE ticketflow_event TEMPLATE template0 ENCODING 'UTF8';
CREATE DATABASE ticketflow_booking TEMPLATE template0 ENCODING 'UTF8';

-- PostgreSQL grants CONNECT/TEMP to PUBLIC by default. Remove that default also on
-- maintenance databases so a domain credential cannot connect to another database.
REVOKE ALL ON DATABASE postgres, template1, ticketflow_customer, ticketflow_event, ticketflow_booking FROM PUBLIC;
GRANT CONNECT, CREATE, TEMPORARY ON DATABASE ticketflow_customer TO ticketflow_customer;
GRANT CONNECT, CREATE, TEMPORARY ON DATABASE ticketflow_event TO ticketflow_event;
GRANT CONNECT, CREATE, TEMPORARY ON DATABASE ticketflow_booking TO ticketflow_booking;

\connect ticketflow_customer
REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT USAGE, CREATE ON SCHEMA public TO ticketflow_customer;

\connect ticketflow_event
REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT USAGE, CREATE ON SCHEMA public TO ticketflow_event;

\connect ticketflow_booking
REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT USAGE, CREATE ON SCHEMA public TO ticketflow_booking;

\connect postgres
CREATE SCHEMA ticketflow_bootstrap;
REVOKE ALL ON SCHEMA ticketflow_bootstrap FROM PUBLIC;
CREATE TABLE ticketflow_bootstrap.initialization (version integer PRIMARY KEY, completed_at timestamptz NOT NULL DEFAULT now());
INSERT INTO ticketflow_bootstrap.initialization (version) VALUES (1);
