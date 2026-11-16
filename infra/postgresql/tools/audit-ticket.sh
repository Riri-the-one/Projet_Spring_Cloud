#!/usr/bin/env bash
set -Eeuo pipefail
export PGCONNECT_TIMEOUT=3
query() {
  PGPASSWORD="$(cat "/run/secrets/postgres_${1}_password")" psql -X -h 127.0.0.1 -U "ticketflow_$1" -d "$2" -Atq -v ON_ERROR_STOP=1 -c "$3"
}
[[ "$(query ticket ticketflow_ticket 'select current_user::text || chr(64) || current_database()::text')" == ticketflow_ticket@ticketflow_ticket ]]
[[ "$(query ticket ticketflow_ticket 'select rolsuper or rolcreatedb or rolcreaterole or rolreplication or rolbypassrls or rolinherit from pg_roles where rolname=current_user')" == f ]]
for domain in customer event booking; do
  for direction in forward reverse; do
    from=ticket; target="ticketflow_$domain"
    if [[ "$direction" == reverse ]]; then from="$domain"; target=ticketflow_ticket; fi
    if output="$(query "$from" "$target" 'select 1' 2>&1)"; then echo 'Unexpected cross-domain access' >&2; exit 1; fi
    [[ "$output" == *'permission denied for database'* ]] || { echo 'Expected CONNECT denial, not a credential/network failure' >&2; exit 1; }
    echo "OK CONNECT denied $from -> $target"
  done
done
for target in postgres template1; do
  if output="$(query ticket "$target" 'select 1' 2>&1)"; then echo 'Unexpected maintenance access' >&2; exit 1; fi
  [[ "$output" == *'permission denied for database'* ]] || exit 1
done
if output="$(PGPASSWORD='invalid-test-only-password' psql -X -h 127.0.0.1 -U ticketflow_ticket -d ticketflow_ticket -Atq -c 'select 1' 2>&1)"; then
  echo 'Invalid Ticket password accepted' >&2; exit 1
fi
[[ "$output" == *'password authentication failed'* ]] || { echo 'Expected Ticket password rejection' >&2; exit 1; }
echo 'OK Ticket TCP authentication and bidirectional domain isolation'
