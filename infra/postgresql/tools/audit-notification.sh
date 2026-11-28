#!/usr/bin/env bash
set -Eeuo pipefail
export PGCONNECT_TIMEOUT=3
query() { PGPASSWORD="$(cat "/run/secrets/postgres_${1}_password")" psql -X -h 127.0.0.1 -U "ticketflow_$1" -d "$2" -Atq -v ON_ERROR_STOP=1 -c "$3"; }
admin() { PGPASSWORD="$(cat "$POSTGRES_PASSWORD_FILE")" psql -X -h 127.0.0.1 -U "$POSTGRES_USER" -d postgres -Atq -v ON_ERROR_STOP=1 -c "$1"; }
[[ "$(query notification ticketflow_notification 'select current_user::text || chr(64) || current_database()::text')" == ticketflow_notification@ticketflow_notification ]]
for domain in customer event booking ticket; do
  [[ "$(admin "select count(*) from pg_database where datname='ticketflow_$domain'")" == 1 ]] || continue
  if output="$(query notification "ticketflow_$domain" 'select 1' 2>&1)"; then echo 'Unexpected Notification cross-domain access' >&2;exit 1;fi
  [[ "$output" == *'permission denied for database'* ]] || { echo 'Expected CONNECT denial' >&2;exit 1; }
  [[ "$(admin "select has_database_privilege('ticketflow_$domain','ticketflow_notification','CONNECT')")" == f ]]
  echo "OK CONNECT denied Notification <-> $domain"
done
for target in postgres template1; do
  if output="$(query notification "$target" 'select 1' 2>&1)"; then exit 1;fi
  [[ "$output" == *'permission denied for database'* ]] || exit 1
done
if output="$(PGPASSWORD='invalid-test-only-password' psql -X -h 127.0.0.1 -U ticketflow_notification -d ticketflow_notification -Atq -c 'select 1' 2>&1)";then exit 1;fi
[[ "$output" == *'password authentication failed'* ]] || exit 1
echo 'OK Notification authentication, wrong password rejection and domain isolation'
