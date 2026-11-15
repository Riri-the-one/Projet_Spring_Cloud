#!/usr/bin/env bash
set -Eeuo pipefail
export PGPASSWORD="$(cat "$POSTGRES_PASSWORD_FILE")"
export PGCONNECT_TIMEOUT=3
# TCP is unavailable during the temporary bootstrap daemon. Also require the final marker,
# so a partial initialization is never reported as healthy after a subsequent start.
psql -X -h 127.0.0.1 -U "$POSTGRES_USER" -d postgres -Atq -v ON_ERROR_STOP=1 \
  -c 'SELECT version FROM ticketflow_bootstrap.initialization WHERE version = 1' | grep -qx 1
