package com.moveinsync.ev.service.observe;

import com.moveinsync.ev.domain.schedule.UnassignedReasonCode;
import com.moveinsync.ev.service.bus.MessageBus;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Process meters. A label is a tier or an {@link UnassignedReasonCode} name. Cab ids, coordinates,
 * and explanation text are not labels.
 */
public final class ServiceMeters implements PublishObserver, DeliveryObserver, OperationalMeters {

  private final Clock clock;
  private final Counter rejections;
  private final Counter conflicts;
  private final Counter duplicates;
  private final Counter late;
  private final Counter retries;
  private final Counter deadLetters;
  private final Counter safePatches;
  private final Counter deadlineMisses;
  private final Counter rejectedEvents;
  private final EnumMap<UnassignedReasonCode, Counter> reasons;
  private final Timer tier0;
  private final Timer tier1;
  private final Timer tier2;
  private final Timer tierOther;
  private final AtomicLong backups = new AtomicLong();
  private final AtomicLong optimalityGap = new AtomicLong();
  private final AtomicLong dlqDepth = new AtomicLong();
  private final AtomicReference<Instant> publishedAt = new AtomicReference<>();

  public ServiceMeters(MeterRegistry registry, Clock clock, MessageBus bus) {
    Objects.requireNonNull(registry, "registry must not be null");
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
    Objects.requireNonNull(bus, "bus must not be null");
    rejections = registry.counter("ev.validator.rejections");
    conflicts = registry.counter("ev.cas.conflicts");
    duplicates = registry.counter("ev.delivery.duplicates");
    late = registry.counter("ev.delivery.late");
    retries = registry.counter("ev.delivery.retries");
    deadLetters = registry.counter("ev.delivery.dlq");
    safePatches = registry.counter("ev.safe.patches");
    deadlineMisses = registry.counter("ev.deadline.misses");
    rejectedEvents = registry.counter("ev.bus.rejected");
    reasons = new EnumMap<>(UnassignedReasonCode.class);
    for (UnassignedReasonCode reason : UnassignedReasonCode.values()) {
      reasons.put(reason, registry.counter("ev.unassigned", "reason", reason.name()));
    }
    tier0 = registry.timer("ev.replan.latency", "tier", "0");
    tier1 = registry.timer("ev.replan.latency", "tier", "1");
    tier2 = registry.timer("ev.replan.latency", "tier", "2");
    tierOther = registry.timer("ev.replan.latency", "tier", "other");
    Gauge.builder("ev.queue.depth", bus, ServiceMeters::depth).register(registry);
    Gauge.builder("ev.consumer.lag", bus, ServiceMeters::lag).register(registry);
    Gauge.builder("ev.plan.version.age.seconds", this, ServiceMeters::ageSeconds)
        .register(registry);
    Gauge.builder("ev.backup.trips", backups, ServiceMeters::read).register(registry);
    Gauge.builder("ev.optimality.gap.micros", optimalityGap, ServiceMeters::read)
        .register(registry);
    Gauge.builder("ev.dlq.depth", dlqDepth, ServiceMeters::read).register(registry);
  }

  @Override
  public void rejected() {
    rejections.increment();
  }

  @Override
  public void conflict() {
    conflicts.increment();
  }

  @Override
  public void published(PublishedSample sample) {
    Objects.requireNonNull(sample, "sample must not be null");
    timer(sample.tier()).record(Duration.ofMillis(Math.max(0L, sample.durationMillis())));
    backups.set(Math.max(0, sample.backups()));
    long gap = sample.chargingCostMicros() - sample.flowLowerBoundMicros();
    optimalityGap.set(Math.max(0L, gap));
    publishedAt.set(clock.instant());
    for (UnassignedReasonCode reason : sample.reasons()) {
      reasons.get(reason).increment();
      if (reason == UnassignedReasonCode.SAFE_PATCH_FALLBACK) {
        safePatches.increment();
      }
    }
  }

  @Override
  public void duplicate() {
    duplicates.increment();
  }

  @Override
  public void late() {
    late.increment();
  }

  @Override
  public void retried() {
    retries.increment();
  }

  @Override
  public void deadLettered() {
    deadLetters.increment();
    dlqDepth.incrementAndGet();
  }

  @Override
  public void rejectedEvent() {
    rejectedEvents.increment();
  }

  @Override
  public void deadlineMiss() {
    deadlineMisses.increment();
  }

  private Timer timer(int tier) {
    if (tier == 0) {
      return tier0;
    }
    if (tier == 1) {
      return tier1;
    }
    if (tier == 2) {
      return tier2;
    }
    return tierOther;
  }

  private double ageSeconds() {
    Instant published = publishedAt.get();
    if (published == null) {
      return 0L;
    }
    return Math.max(0L, Duration.between(published, clock.instant()).getSeconds());
  }

  private static double depth(MessageBus bus) {
    return bus.queued();
  }

  private static double lag(MessageBus bus) {
    return bus.consumerLag();
  }

  private static double read(AtomicLong value) {
    return value.get();
  }
}
