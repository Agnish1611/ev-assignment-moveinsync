package com.moveinsync.ev.service.bus;

import static org.assertj.core.api.Assertions.assertThat;

import com.moveinsync.ev.domain.schedule.UnassignedReasonCode;
import com.moveinsync.ev.service.observe.PublishedSample;
import com.moveinsync.ev.service.observe.ServiceMeters;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

/** Meters use a tier or a reason code. The scrape has no personal data. */
class ServiceMetersTest {

  private static final Instant START = Instant.parse("2026-10-30T08:00:00Z");

  @Test
  void publicationAndDeliveryUpdateTheCounters() {
    MemoryMessageBus bus = new MemoryMessageBus();
    MovingClock clock = new MovingClock(START);
    PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
    ServiceMeters meters = new ServiceMeters(registry, clock, bus);
    meters.rejected();
    meters.conflict();
    meters.deadlineMiss();
    meters.published(new PublishedSample(9, 5L, 0, 10L, 80L, List.of()));
    meters.published(
        new PublishedSample(
            1,
            40L,
            2,
            100L,
            40L,
            List.of(UnassignedReasonCode.SAFE_PATCH_FALLBACK, UnassignedReasonCode.OTHER)));
    clock.advance(Duration.ofSeconds(90));

    Delivery delivery =
        new Delivery(bus, new SeenSequences(), clock, BusSettings.standard(), meters);
    delivery.attempt(trigger(1L), envelope -> {});
    delivery.attempt(trigger(1L), envelope -> {});
    delivery.attempt(trigger(0L), envelope -> {});
    delivery.attempt(
        new BusRecord(Topics.TELEMETRY, "k", new byte[] {1}, Map.of("attempt", "2"), -1, -1),
        envelope -> {});
    bus.pause(Topics.TELEMETRY);
    bus.send(BusRecord.of(Topics.TELEMETRY, "held", new byte[] {1}));

    assertThat(registry.get("ev.validator.rejections").counter().count()).isEqualTo(1);
    assertThat(registry.get("ev.cas.conflicts").counter().count()).isEqualTo(1);
    assertThat(registry.get("ev.deadline.misses").counter().count()).isEqualTo(1);
    assertThat(registry.get("ev.safe.patches").counter().count()).isEqualTo(1);
    assertThat(registry.get("ev.backup.trips").gauge().value()).isEqualTo(2);
    assertThat(registry.get("ev.optimality.gap.micros").gauge().value()).isEqualTo(60);
    assertThat(registry.get("ev.replan.latency").tag("tier", "1").timer().count()).isEqualTo(1);
    assertThat(registry.get("ev.replan.latency").tag("tier", "other").timer().count()).isEqualTo(1);
    assertThat(registry.get("ev.delivery.duplicates").counter().count()).isEqualTo(1);
    assertThat(registry.get("ev.delivery.late").counter().count()).isEqualTo(1);
    assertThat(registry.get("ev.delivery.dlq").counter().count()).isEqualTo(1);
    assertThat(registry.get("ev.dlq.depth").gauge().value()).isEqualTo(1);
    assertThat(registry.get("ev.queue.depth").gauge().value()).isEqualTo(1);
    assertThat(registry.get("ev.plan.version.age.seconds").gauge().value()).isEqualTo(90);
    String scrape = registry.scrape();
    assertThat(scrape).contains("ev_deadline_misses_total");
    assertThat(scrape).contains("ev_plan_version_age_seconds");
    assertThat(scrape).contains("reason=\"OTHER\"");
    assertThat(scrape).doesNotContain("@");
  }

  private static BusRecord trigger(long sequence) {
    Envelope envelope =
        new Envelope(
            "event-" + sequence,
            "tenant-a",
            "device",
            sequence,
            START,
            Envelope.SCHEMA_VERSION,
            new Envelope.PlanTrigger("LATE_CAB", "CAB-1", "shift-1", START, OptionalLong.empty()));
    return BusRecord.of(Topics.PLAN_TRIGGERS, "tenant-a:shift-1", EnvelopeCodec.write(envelope));
  }

  private static final class MovingClock extends Clock {
    private Instant current;

    private MovingClock(Instant current) {
      this.current = current;
    }

    private void advance(Duration duration) {
      current = current.plus(duration);
    }

    @Override
    public Instant instant() {
      return current;
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }
  }
}
