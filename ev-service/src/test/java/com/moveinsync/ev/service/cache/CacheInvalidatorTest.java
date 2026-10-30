package com.moveinsync.ev.service.cache;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.moveinsync.ev.service.bus.BusRecord;
import com.moveinsync.ev.service.bus.Envelope;
import com.moveinsync.ev.service.bus.EnvelopeCodec;
import com.moveinsync.ev.service.bus.MemoryMessageBus;
import com.moveinsync.ev.service.bus.Topics;
import com.moveinsync.ev.service.observe.ServiceMeters;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/** A published head drops one pointer. A config event drops one tenant. Poison is not logged. */
class CacheInvalidatorTest {

  private static final Instant AT = Instant.parse("2026-10-30T08:00:00Z");

  @Test
  void planAndConfigEventsEvictOnlyTheNamedTenant() {
    MemoryMessageBus bus = new MemoryMessageBus();
    PlanCaches caches =
        new PlanCaches(new CacheSettings(8, 8, 8, 8, 2, 60, 24), new ManualTicker());
    ServiceMeters meters = meters(bus);
    CacheInvalidator invalidator = new CacheInvalidator(bus, caches, meters);
    invalidator.start();
    bus.subscribe(Topics.TELEMETRY, (record, ack) -> ack.run());
    caches.current("tenant-a", "shard-a", () -> 1L);
    caches.current("tenant-a", "shard-b", () -> 1L);
    caches.fleet("tenant-a", "HUB", "HUB-1", () -> "a");
    caches.fleet("tenant-b", "HUB", "HUB-1", () -> "b");

    bus.send(
        BusRecord.of(Topics.PLAN_EVENTS, "tenant-a:shard-a", published("tenant-a", "shard-a")));
    bus.send(BusRecord.of(Topics.CONFIG, "tenant-a:HUB", changed("tenant-a", "HUB-1")));

    AtomicInteger loads = new AtomicInteger();
    assertThat(caches.current("tenant-a", "shard-a", () -> load(loads))).isEqualTo(9L);
    assertThat(caches.current("tenant-a", "shard-b", () -> load(loads))).isEqualTo(1L);
    assertThat(caches.fleet("tenant-a", "HUB", "HUB-1", () -> "reloaded")).isEqualTo("reloaded");
    assertThat(caches.fleet("tenant-b", "HUB", "HUB-1", () -> "other")).isEqualTo("b");
    assertThat(loads).hasValue(1);
  }

  @Test
  void aPoisonBodyIsCountedAndNotLogged() {
    MemoryMessageBus bus = new MemoryMessageBus();
    PlanCaches caches =
        new PlanCaches(new CacheSettings(4, 4, 4, 4, 2, 60, 24), new ManualTicker());
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    ServiceMeters meters = new ServiceMeters(registry, Clock.fixed(AT, ZoneOffset.UTC), bus);
    new CacheInvalidator(bus, caches, meters).start();
    Logger logger = (Logger) LoggerFactory.getLogger(CacheInvalidator.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    try {
      bus.send(
          BusRecord.of(
              Topics.CONFIG,
              "tenant-a:HUB",
              "person@example.com".getBytes(StandardCharsets.UTF_8)));
    } finally {
      logger.detachAppender(appender);
    }

    assertThat(registry.get("ev.bus.rejected").counter().count()).isEqualTo(1.0);
    assertThat(appender.list).isNotEmpty();
    for (ILoggingEvent event : appender.list) {
      assertThat(event.getFormattedMessage()).doesNotContain("@");
    }
  }

  private static ServiceMeters meters(MemoryMessageBus bus) {
    return new ServiceMeters(new SimpleMeterRegistry(), Clock.fixed(AT, ZoneOffset.UTC), bus);
  }

  private static long load(AtomicInteger loads) {
    loads.incrementAndGet();
    return 9L;
  }

  private static byte[] published(String tenant, String shard) {
    Envelope envelope =
        new Envelope(
            "event-1",
            tenant,
            "outbox",
            1L,
            AT,
            Envelope.SCHEMA_VERSION,
            new Envelope.PlanPublished(shard, 4L));
    return EnvelopeCodec.write(envelope);
  }

  private static byte[] changed(String tenant, String subject) {
    Envelope envelope =
        new Envelope(
            "event-2",
            tenant,
            "admin",
            1L,
            AT,
            Envelope.SCHEMA_VERSION,
            new Envelope.ConfigChanged("HUB", subject));
    return EnvelopeCodec.write(envelope);
  }

  private static final class ManualTicker implements com.github.benmanes.caffeine.cache.Ticker {
    @Override
    public long read() {
      return 0L;
    }
  }
}
