#!/usr/bin/env bash
set -Eeuo pipefail
export PGCONNECT_TIMEOUT=3
query(){ PGPASSWORD="$(cat "/run/secrets/postgres_${1}_password")" psql -X -h 127.0.0.1 -U "ticketflow_$1" -d "$2" -Atq -v ON_ERROR_STOP=1 -c "$3"; }
for domain in customer event booking ticket notification; do
  db="ticketflow_$domain"
  [[ "$(query "$domain" "$db" 'select current_user::text || chr(64) || current_database()::text')" == "$db@$db" ]]
  [[ "$(query "$domain" "$db" 'select rolsuper or rolcreatedb or rolcreaterole or rolreplication or rolbypassrls from pg_roles where rolname=current_user')" == f ]]
  for other in customer event booking ticket notification; do
    [[ "$domain" == "$other" ]] && continue
    if output="$(query "$domain" "ticketflow_$other" 'select 1' 2>&1)"; then echo 'Unexpected cross-domain access' >&2;exit 1;fi
    [[ "$output" == *'permission denied for database'* ]] || { echo 'Expected CONNECT denial, not another failure' >&2;exit 1; }
  done
  echo "OK $domain authenticated; four cross-domain connections denied"
done
