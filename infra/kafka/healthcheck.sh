#!/usr/bin/env bash
set -Eeuo pipefail
export KAFKA_HEAP_OPTS='-Xms32m -Xmx128m'
status="$(/opt/kafka/bin/kafka-metadata-quorum.sh --bootstrap-server kafka:9092 \
  --command-config /opt/ticketflow-kafka/client.properties describe --status)"
grep -Eq "^ClusterId:[[:space:]]+$CLUSTER_ID[[:space:]]*$" <<< "$status"
grep -Eq '^LeaderId:[[:space:]]+1[[:space:]]*$' <<< "$status"
