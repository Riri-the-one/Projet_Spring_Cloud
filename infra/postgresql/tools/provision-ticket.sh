#!/usr/bin/env bash
set -Eeuo pipefail
export PGCONNECT_TIMEOUT=5
export PGPASSWORD="$(cat "$POSTGRES_PASSWORD_FILE")"
psql -X -h 127.0.0.1 -U "$POSTGRES_USER" -d postgres -v ON_ERROR_STOP=1 \
  -f /opt/ticketflow-postgresql/provision-ticket.sql >/dev/null
echo 'OK Ticket domain provisioned/verified; no existing password, business database or table altered.'
