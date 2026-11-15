#!/usr/bin/env bash
set -Eeuo pipefail
export PGCONNECT_TIMEOUT=3
export PGOPTIONS='-c client_min_messages=warning'

query() {
  local domain="$1" database="$2" sql="$3"
  PGPASSWORD="$(cat "/run/secrets/postgres_${domain}_password")" \
    psql -X -h 127.0.0.1 -U "ticketflow_${domain}" -d "$database" -Atq -v ON_ERROR_STOP=1 -c "$sql"
}

for domain in customer event booking; do
  database="ticketflow_${domain}"
  actual="$(query "$domain" "$database" 'SELECT current_user::text || chr(64) || current_database()::text')"
  [[ "$actual" == "${database}@${database}" ]] || { echo 'Unexpected identity/database' >&2; exit 1; }
  echo "OK TCP SCRAM ${database}"

  flags="$(query "$domain" "$database" 'SELECT rolsuper OR rolcreatedb OR rolcreaterole OR rolreplication OR rolbypassrls FROM pg_roles WHERE rolname = current_user')"
  [[ "$flags" == f ]] || { echo 'Privileged domain account' >&2; exit 1; }
  memberships="$(query "$domain" "$database" 'SELECT count(*) FROM pg_auth_members WHERE member = (SELECT oid FROM pg_roles WHERE rolname = current_user)')"
  [[ "$memberships" == 0 ]] || { echo 'Unexpected role membership' >&2; exit 1; }
  owner="$(query "$domain" "$database" 'SELECT pg_get_userbyid(datdba) FROM pg_database WHERE datname = current_database()')"
  [[ "$owner" == ticketflow_admin ]] || { echo 'Unexpected database owner' >&2; exit 1; }

  # A separate audit schema contains no business tables and is never reset by this script.
  query "$domain" "$database" "CREATE SCHEMA IF NOT EXISTS ticketflow_infra_probe;
    CREATE TABLE IF NOT EXISTS ticketflow_infra_probe.persistence (
      id integer PRIMARY KEY CHECK (id = 1), marker text NOT NULL, created_at timestamptz NOT NULL DEFAULT now());
    INSERT INTO ticketflow_infra_probe.persistence (id, marker)
      VALUES (1, md5(random()::text || clock_timestamp()::text)) ON CONFLICT (id) DO NOTHING;" >/dev/null
  marker="$(query "$domain" "$database" 'SELECT marker FROM ticketflow_infra_probe.persistence WHERE id = 1')"
  [[ "$marker" =~ ^[a-f0-9]{32}$ ]] || { echo 'Invalid persistence marker' >&2; exit 1; }
  echo "MARKER ${domain} ${marker}"

  for other in customer event booking postgres template1; do
    if [[ "$other" == "$domain" ]]; then continue; fi
    target="$other"
    if [[ "$other" != postgres && "$other" != template1 ]]; then target="ticketflow_${other}"; fi
    # Verify the exact PostgreSQL permission error: a network/password failure is NOT proof of isolation.
    if output="$(query "$domain" "$target" 'SELECT 1' 2>&1)"; then
      echo "Cross-domain connection unexpectedly accepted: ${domain} -> ${target}" >&2; exit 1
    fi
    if [[ "$output" != *'permission denied for database'* ]]; then
      echo "Expected CONNECT denial, received a different failure: ${domain} -> ${target}" >&2; exit 1
    fi
    echo "OK CONNECT denied ${domain} -> ${target}"
    if [[ "$other" != postgres && "$other" != template1 ]]; then
      if output="$(PGPASSWORD="$(cat "/run/secrets/postgres_${domain}_password")" \
          psql -X -h 127.0.0.1 -U "ticketflow_${other}" -d "$target" -Atq -c 'SELECT 1' 2>&1)"; then
        echo 'Domain credentials unexpectedly interchangeable' >&2; exit 1
      fi
      [[ "$output" == *'password authentication failed'* ]] || { echo 'Expected different domain passwords' >&2; exit 1; }
      echo "OK credential cannot impersonate ${domain} -> ${other}"
    fi
  done
  if output="$(PGPASSWORD='intentionally-invalid-test-password' psql -X -h 127.0.0.1 -U "$database" -d "$database" \
       -Atq -c 'SELECT 1' 2>&1)"; then
    echo 'Invalid password unexpectedly accepted' >&2; exit 1
  fi
  [[ "$output" == *'password authentication failed'* ]] || { echo 'Expected a password authentication failure' >&2; exit 1; }
  echo "OK wrong password rejected ${domain}"
done
echo 'PostgreSQL domain isolation and persistent probes verified.'
