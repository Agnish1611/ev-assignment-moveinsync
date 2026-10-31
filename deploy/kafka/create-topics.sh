#!/bin/sh
# Idempotent topic init. Dev replication factor is 1. Production is 3 with min.insync.replicas=2.
set -eu
bootstrap="${1:-kafka:9092}"
topics="
ev.plan-triggers.v1
ev.plan-triggers.v1.retry.5s
ev.plan-triggers.v1.retry.1m
ev.plan-triggers.v1.dlq
ev.telemetry.v1
ev.telemetry.v1.retry.5s
ev.telemetry.v1.retry.1m
ev.telemetry.v1.dlq
ev.plan-events.v1
ev.plan-events.v1.retry.5s
ev.plan-events.v1.retry.1m
ev.plan-events.v1.dlq
ev.charger-status.v1
ev.charger-status.v1.retry.5s
ev.charger-status.v1.retry.1m
ev.charger-status.v1.dlq
ev.config.v1
ev.config.v1.retry.5s
ev.config.v1.retry.1m
ev.config.v1.dlq
"
for topic in $topics; do
  /opt/kafka/bin/kafka-topics.sh \
    --bootstrap-server "$bootstrap" \
    --create \
    --if-not-exists \
    --topic "$topic" \
    --partitions 6 \
    --replication-factor 1
done
