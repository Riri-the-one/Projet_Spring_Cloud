#!/usr/bin/env bash
set -Eeuo pipefail
export KAFKA_HEAP_OPTS='-Xms32m -Xmx128m'
mode="${1:?write or verify required}"
run="${2:?probe run id required}"
[[ "$run" =~ ^[a-f0-9]{32}$ ]] || { echo 'Invalid probe id' >&2; exit 1; }
topic=ticketflow.infra.probe.v1
bootstrap=kafka:9092
bin=/opt/kafka/bin
expected="$(printf 'probe-a:infrastructure-only:%s:one\nprobe-b:infrastructure-only:%s:two' "$run" "$run")"
if [[ "$mode" == write ]]; then
  "$bin/kafka-topics.sh" --bootstrap-server "$bootstrap" --command-config /opt/ticketflow-kafka/client.properties \
    --create --if-not-exists --topic "$topic" --partitions 1 --replication-factor 1 \
    --config retention.ms=604800000 --config cleanup.policy=delete >/dev/null
  offset="$("$bin/kafka-get-offsets.sh" --bootstrap-server "$bootstrap" --topic "$topic" --time -1 | awk -F: '{print $3}')"
  [[ "$offset" =~ ^[0-9]+$ ]] || { echo 'Invalid probe topic offset' >&2; exit 1; }
  printf '%s\n' "$expected" | "$bin/kafka-console-producer.sh" --bootstrap-server "$bootstrap" --topic "$topic" --sync \
    --reader-property parse.key=true --reader-property key.separator=: --command-property acks=all \
    --command-property enable.idempotence=true --command-property delivery.timeout.ms=15000 \
    --command-property request.timeout.ms=5000 --command-property max.block.ms=15000
elif [[ "$mode" == verify ]]; then
  offset="${3:?saved offset required}"
  [[ "$offset" =~ ^[0-9]+$ ]] || { echo 'Invalid saved offset' >&2; exit 1; }
else
  echo 'Unknown probe mode' >&2; exit 1
fi
actual="$("$bin/kafka-console-consumer.sh" --bootstrap-server "$bootstrap" --topic "$topic" \
  --partition 0 --offset "$offset" --max-messages 2 --timeout-ms 20000 \
  --formatter-property print.key=true --formatter-property key.separator=: --command-property enable.auto.commit=false \
  --command-property isolation.level=read_committed)"
[[ "$actual" == "$expected" ]] || { echo 'Probe records differ from the published messages' >&2; exit 1; }
echo "PROBE $run OFFSET $offset"
echo "OK $mode: two technical records consumed in order from a separate Docker client"
