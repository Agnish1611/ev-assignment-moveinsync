package com.moveinsync.ev.service.bus;

import com.moveinsync.ev.planner.solve.DriftEvent;
import com.moveinsync.ev.planner.telemetry.TelemetryGate;
import java.time.Instant;
import java.util.Objects;

/**
 * Turns a telemetry envelope into a plan trigger when the gate says the reading matters. The
 * trigger keeps the reading's producer and sequence, so a replay does not solve twice.
 */
public final class DriftDetector {

  private final MessageBus bus;
  private final TelemetryGate gate;
  private final ExpectedCharge expected;
  private final String tenant;
  private final String shard;
  private long silenceSequence;

  public DriftDetector(
      MessageBus bus, TelemetryGate gate, ExpectedCharge expected, String tenant, String shard) {
    this.bus = Objects.requireNonNull(bus, "bus must not be null");
    this.gate = Objects.requireNonNull(gate, "gate must not be null");
    this.expected = Objects.requireNonNull(expected, "expected must not be null");
    this.tenant = tenant;
    this.shard = shard;
  }

  public void start() {
    bus.subscribe(Topics.TELEMETRY, this::onRecord);
  }

  public void silence(Instant now) {
    for (DriftEvent event : gate.silence(now)) {
      silenceSequence++;
      publish("silence", silenceSequence, event);
    }
  }

  private void onRecord(BusRecord record, Runnable ack) {
    Envelope envelope = EnvelopeCodec.read(record.value());
    if (tenant.equals(envelope.tenant())
        && envelope.payload() instanceof Envelope.Telemetry telemetry) {
      gate.observe(
              telemetry.cabId(),
              telemetry.socWh(),
              expected.expectedWh(telemetry.cabId()),
              envelope.eventTime())
          .ifPresent(event -> publish(envelope.producer(), envelope.sequence(), event));
    }
    ack.run();
  }

  private void publish(String producer, long sequence, DriftEvent event) {
    Envelope envelope =
        new Envelope(
            tenant + ":" + producer + ":" + sequence,
            tenant,
            producer,
            sequence,
            event.observedAt(),
            Envelope.SCHEMA_VERSION,
            new Envelope.PlanTrigger(
                event.kind().name(),
                event.subjectId(),
                shard,
                event.observedAt(),
                event.reportedSocWh()));
    bus.send(
        BusRecord.of(Topics.PLAN_TRIGGERS, tenant + ":" + shard, EnvelopeCodec.write(envelope)));
  }
}
