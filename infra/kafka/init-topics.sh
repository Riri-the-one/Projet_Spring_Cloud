#!/usr/bin/env bash
set -Eeuo pipefail
export KAFKA_HEAP_OPTS='-Xms32m -Xmx128m'
topics=/opt/kafka/bin/kafka-topics.sh
config=/opt/ticketflow-kafka/client.properties
while read -r topic partitions retention; do
  [[ -z "$topic" || "$topic" == \#* ]] && continue
  "$topics" --bootstrap-server kafka:9092 --command-config "$config" --create --if-not-exists \
    --topic "$topic" --partitions "$partitions" --replication-factor 1 \
    --config "retention.ms=$retention" --config cleanup.policy=delete
  description="$("$topics" --bootstrap-server kafka:9092 --command-config "$config" --describe --topic "$topic")"
  grep -Eq "PartitionCount:[[:space:]]*$partitions([[:space:]]|$)" <<< "$description"
  grep -Eq 'ReplicationFactor:[[:space:]]*1([[:space:]]|$)' <<< "$description"
  grep -Fq "retention.ms=$retention" <<< "$description"
  grep -Fq 'cleanup.policy=delete' <<< "$description"
  echo "OK topic $topic partitions=$partitions replication=1 retention.ms=$retention"
done < /opt/ticketflow-kafka/topics.tsv
