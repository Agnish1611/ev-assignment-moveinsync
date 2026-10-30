package com.moveinsync.ev.service.bus;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/** An older published version does not roll the projection backward. */
class PlanProjectionTest {

  private static final Instant AT = Instant.parse("2026-10-30T08:00:00Z");

  @Test
  void anOlderVersionIsIgnored() {
    PlanProjection projection = new PlanProjection();

    projection.observe(published(2L));
    projection.observe(published(1L));
    projection.observe(published(2L));

    assertThat(projection.version("shift-1")).isEqualTo(2L);
    assertThat(projection.version("shift-2")).isZero();
  }

  @Test
  void aPlanEventUpdatesTheProjection() {
    MemoryMessageBus bus = new MemoryMessageBus();
    PlanProjection projection = new PlanProjection();
    projection.start(bus);

    bus.send(
        BusRecord.of(Topics.PLAN_EVENTS, "tenant-a:shift-1", EnvelopeCodec.write(published(4L))));

    assertThat(projection.version("shift-1")).isEqualTo(4L);
  }

  private static Envelope published(long version) {
    return new Envelope(
        "tenant-a:shift-1:" + version,
        "tenant-a",
        "outbox",
        version,
        AT,
        Envelope.SCHEMA_VERSION,
        new Envelope.PlanPublished("shift-1", version));
  }
}
