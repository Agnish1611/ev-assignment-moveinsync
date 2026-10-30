package com.moveinsync.ev.service.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.benmanes.caffeine.cache.Ticker;
import com.moveinsync.ev.service.api.ApiModels.PlanVersionView;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * A published version stays cached. The current pointer expires. A loader failure is not stored.
 */
class PlanCacheTest {

  @Test
  void theCurrentPointerExpiresAndAVersionDoesNot() {
    ManualTicker ticker = new ManualTicker();
    CacheSettings settings = new CacheSettings(8, 8, 8, 8, 2, 60, 24);
    PlanCaches caches = new PlanCaches(settings, ticker);
    AtomicInteger currentLoads = new AtomicInteger();
    AtomicInteger versionLoads = new AtomicInteger();

    assertThat(caches.current("tenant-a", "shard", () -> load(currentLoads, 1L))).isEqualTo(1L);
    assertThat(caches.current("tenant-a", "shard", () -> load(currentLoads, 2L))).isEqualTo(1L);
    assertThat(caches.version("tenant-a", "shard", 1L, () -> view(versionLoads, "first")))
        .extracting(PlanVersionView::planId)
        .isEqualTo("first");

    ticker.advance(Duration.ofDays(2));
    assertThat(caches.version("tenant-a", "shard", 1L, () -> view(versionLoads, "second")))
        .extracting(PlanVersionView::planId)
        .isEqualTo("first");
    assertThat(caches.current("tenant-a", "shard", () -> load(currentLoads, 2L))).isEqualTo(2L);
    assertThat(currentLoads).hasValue(2);
    assertThat(versionLoads).hasValue(1);
  }

  @Test
  void fleetEvictionIsLimitedToThatTenant() {
    PlanCaches caches =
        new PlanCaches(new CacheSettings(4, 4, 4, 4, 2, 60, 24), new ManualTicker());
    caches.fleet("tenant-a", "HUB", "HUB-1", () -> "a");
    caches.fleet("tenant-b", "HUB", "HUB-1", () -> "b");
    caches.evictFleet("tenant-a");
    AtomicInteger loads = new AtomicInteger();

    assertThat(caches.fleet("tenant-a", "HUB", "HUB-1", () -> loadText(loads)))
        .isEqualTo("reloaded");
    assertThat(caches.fleet("tenant-b", "HUB", "HUB-1", () -> loadText(loads))).isEqualTo("b");
    assertThat(loads).hasValue(1);
  }

  @Test
  void theVersionCacheStaysInsideItsSizeBound() {
    PlanCaches caches =
        new PlanCaches(new CacheSettings(2, 2, 2, 2, 2, 60, 24), new ManualTicker());
    AtomicInteger loads = new AtomicInteger();
    for (long version = 1L; version <= 30L; version++) {
      long current = version;
      caches.version("tenant-a", "shard", current, () -> view(loads, "plan-" + current));
    }
    caches.cleanUp();

    assertThat(caches.retainedVersions()).isLessThanOrEqualTo(2L);
    int loaded = loads.get();
    for (long version = 1L; version <= 30L; version++) {
      long current = version;
      caches.version("tenant-a", "shard", current, () -> view(loads, "plan-" + current));
    }
    assertThat(loads.get()).isGreaterThan(loaded);
  }

  @Test
  void aFailedLoadIsNotCached() {
    PlanCaches caches =
        new PlanCaches(new CacheSettings(4, 4, 4, 4, 2, 60, 24), new ManualTicker());
    AtomicInteger loads = new AtomicInteger();

    assertThatThrownBy(
            () ->
                caches.current(
                    "tenant-a",
                    "shard",
                    () -> {
                      loads.incrementAndGet();
                      throw new IllegalStateException("missing");
                    }))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(
            () ->
                caches.current(
                    "tenant-a",
                    "shard",
                    () -> {
                      loads.incrementAndGet();
                      throw new IllegalStateException("missing");
                    }))
        .isInstanceOf(IllegalStateException.class);
    assertThat(loads).hasValue(2);
  }

  private static long load(AtomicInteger loads, long value) {
    loads.incrementAndGet();
    return value;
  }

  private static String loadText(AtomicInteger loads) {
    loads.incrementAndGet();
    return "reloaded";
  }

  private static PlanVersionView view(AtomicInteger loads, String planId) {
    loads.incrementAndGet();
    return new PlanVersionView("shard", 1L, null, planId, "2026-10-30T08:00:00Z");
  }

  private static final class ManualTicker implements Ticker {
    private long nanos;

    void advance(Duration duration) {
      nanos = Math.addExact(nanos, duration.toNanos());
    }

    @Override
    public long read() {
      return nanos;
    }
  }
}
