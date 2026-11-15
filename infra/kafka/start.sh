#!/usr/bin/env bash
set -Eeuo pipefail
data=/var/lib/kafka/data
config=/tmp/ticketflow-server.properties
: "${CLUSTER_ID:?A stable cluster identifier is required}"
port="${TICKETFLOW_KAFKA_PORT:-29092}"
[[ "$CLUSTER_ID" =~ ^[A-Za-z0-9_-]{22}$ ]] || { echo 'Invalid cluster identifier' >&2; exit 1; }
[[ "$port" =~ ^[0-9]{1,5}$ ]] && (( port > 0 && port <= 65535 )) || { echo 'Invalid host port' >&2; exit 1; }
cat /opt/ticketflow-kafka/server.properties > "$config"
printf '\nadvertised.listeners=INTERNAL://kafka:9092,HOST://127.0.0.1:%s\n' "$port" >> "$config"

# The official image wrapper still formats static voters. Use the official storage CLI
# directly for modern standalone/dynamic KRaft, and NEVER format an existing data directory.
if [[ -f "$data/meta.properties" ]]; then
  grep -Fxq "cluster.id=$CLUSTER_ID" "$data/meta.properties" || { echo 'Cluster identifier differs from persisted storage' >&2; exit 1; }
  grep -Fxq 'node.id=1' "$data/meta.properties" || { echo 'Unexpected persisted node identifier' >&2; exit 1; }
else
  [[ -z "$(find "$data" -mindepth 1 -maxdepth 1 -print -quit)" ]] || {
    echo 'Storage is not empty and has no metadata; refusing automatic formatting' >&2; exit 1;
  }
  /opt/kafka/bin/kafka-storage.sh format --standalone --cluster-id "$CLUSTER_ID" --config "$config"
fi
exec /opt/kafka/bin/kafka-server-start.sh "$config"
