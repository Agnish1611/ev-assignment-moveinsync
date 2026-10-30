package com.moveinsync.ev.service.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import com.moveinsync.ev.service.api.ApiModels.PlanVersionView;
import com.moveinsync.ev.service.api.ApiModels.TimelineView;
import com.moveinsync.ev.service.api.ApiModels.UnassignedView;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/** Caffeine caches for plan reads. Entries are created here, not passed in. */
public final class PlanCaches implements PlanReadCache {

  private final Cache<String, Long> current;
  private final Cache<String, PlanVersionView> versions;
  private final Cache<String, String> reports;
  private final Cache<String, List<UnassignedView>> unassigned;
  private final Cache<String, TimelineView> timelines;
  private final Cache<String, String> fleet;

  public PlanCaches(CacheSettings settings, Ticker ticker) {
    Objects.requireNonNull(settings, "settings must not be null");
    Objects.requireNonNull(ticker, "ticker must not be null");
    current = expiring(settings.currentEntries(), settings.currentTtlSeconds(), ticker);
    versions = bounded(settings.versionEntries());
    reports = bounded(settings.versionEntries());
    unassigned = bounded(settings.versionEntries());
    timelines = bounded(settings.versionEntries());
    fleet = expiring(settings.fleetEntries(), settings.fleetTtlSeconds(), ticker);
  }

  @Override
  public long current(String tenant, String shard, Supplier<Long> load) {
    Long found = load(current, currentKey(tenant, shard), load);
    return found.longValue();
  }

  @Override
  public PlanVersionView version(
      String tenant, String shard, long version, Supplier<PlanVersionView> load) {
    return load(versions, versionKey(tenant, shard, version, "version"), load);
  }

  @Override
  public String report(String tenant, String shard, long version, Supplier<String> load) {
    return load(reports, versionKey(tenant, shard, version, "report"), load);
  }

  @Override
  public List<UnassignedView> unassigned(
      String tenant, String shard, long version, Supplier<List<UnassignedView>> load) {
    List<UnassignedView> found =
        load(
            unassigned,
            versionKey(tenant, shard, version, "unassigned"),
            () -> List.copyOf(load.get()));
    return List.copyOf(found);
  }

  @Override
  public TimelineView timeline(
      String tenant, String shard, long version, String cabId, Supplier<TimelineView> load) {
    return load(timelines, versionKey(tenant, shard, version, "timeline|" + cabId), load);
  }

  @Override
  public String fleet(String tenant, String kind, String id, Supplier<String> load) {
    return load(fleet, tenant + "|" + kind + "|" + id, load);
  }

  @Override
  public void evictCurrent(String tenant, String shard) {
    current.invalidate(currentKey(tenant, shard));
  }

  @Override
  public void evictFleet(String tenant) {
    String prefix = tenant + "|";
    for (String key : List.copyOf(fleet.asMap().keySet())) {
      if (key.startsWith(prefix)) {
        fleet.invalidate(key);
      }
    }
  }

  void cleanUp() {
    current.cleanUp();
    versions.cleanUp();
    reports.cleanUp();
    unassigned.cleanUp();
    timelines.cleanUp();
    fleet.cleanUp();
  }

  long retainedVersions() {
    versions.cleanUp();
    return versions.estimatedSize();
  }

  private static <T> T load(Cache<String, T> cache, String key, Supplier<T> source) {
    return cache.get(key, ignored -> source.get());
  }

  private static <T> Cache<String, T> bounded(int entries) {
    return Caffeine.newBuilder().maximumSize(entries).build();
  }

  private static <T> Cache<String, T> expiring(int entries, long ttlSeconds, Ticker ticker) {
    return Caffeine.newBuilder()
        .maximumSize(entries)
        .expireAfterWrite(Duration.ofSeconds(ttlSeconds))
        .ticker(ticker)
        .build();
  }

  private static String currentKey(String tenant, String shard) {
    return tenant + "|" + shard + "|current";
  }

  private static String versionKey(String tenant, String shard, long version, String part) {
    return tenant + "|" + shard + "|" + version + "|" + part;
  }
}
