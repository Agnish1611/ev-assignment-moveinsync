package com.moveinsync.ev.service.bus;

import java.time.Instant;
import java.util.Objects;
import java.util.OptionalLong;

/** Publishes validated telemetry and charger status. It does not decide whether to replan. */
public final class TelemetryGateway {

  private final MessageBus bus;
  private final String tenant;
  private final String producer;

  public TelemetryGateway(MessageBus bus, String tenant, String producer) {
    this.bus = Objects.requireNonNull(bus, "bus must not be null");
    this.tenant = tenant;
    this.producer = producer;
  }

  public void publishReading(String cabId, long socWh, Instant observedAt, long sequence) {
    Envelope envelope =
        new Envelope(
            id(sequence),
            tenant,
            producer,
            sequence,
            observedAt,
            Envelope.SCHEMA_VERSION,
            new Envelope.Telemetry(cabId, socWh));
    bus.send(BusRecord.of(Topics.TELEMETRY, tenant + ":" + cabId, EnvelopeCodec.write(envelope)));
  }

  public void publishCharger(
      String hubId,
      String chargerId,
      boolean operational,
      String shard,
      Instant observedAt,
      long sequence) {
    Envelope status =
        new Envelope(
            id(sequence),
            tenant,
            producer,
            sequence,
            observedAt,
            Envelope.SCHEMA_VERSION,
            new Envelope.ChargerStatus(hubId, chargerId, operational));
    bus.send(
        BusRecord.of(Topics.CHARGER_STATUS, tenant + ":" + hubId, EnvelopeCodec.write(status)));
    if (operational) {
      return;
    }
    Envelope trigger =
        new Envelope(
            id(sequence) + ":fail",
            tenant,
            producer,
            sequence,
            observedAt,
            Envelope.SCHEMA_VERSION,
            new Envelope.PlanTrigger(
                "CHARGER_FAILURE", chargerId, shard, observedAt, OptionalLong.empty()));
    bus.send(
        BusRecord.of(Topics.PLAN_TRIGGERS, tenant + ":" + shard, EnvelopeCodec.write(trigger)));
  }

  private String id(long sequence) {
    return tenant + ":" + producer + ":" + sequence;
  }
}
