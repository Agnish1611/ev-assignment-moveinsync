package com.moveinsync.ev.service.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import com.moveinsync.ev.domain.distance.DistanceDuration;
import com.moveinsync.ev.domain.distance.DistanceProvider;
import com.moveinsync.ev.domain.location.Location;
import java.time.Duration;
import java.util.Objects;

/**
 * Caches base distance by location id. Callers apply traffic outside this cache, so a hit never
 * carries a coordinate.
 */
public final class DistanceCache implements DistanceProvider {

  private final DistanceProvider delegate;
  private final Cache<String, DistanceDuration> cache;

  public DistanceCache(DistanceProvider delegate, CacheSettings settings, Ticker ticker) {
    this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
    Objects.requireNonNull(settings, "settings must not be null");
    Objects.requireNonNull(ticker, "ticker must not be null");
    cache =
        Caffeine.newBuilder()
            .maximumSize(settings.distanceEntries())
            .expireAfterWrite(Duration.ofHours(settings.distanceTtlHours()))
            .ticker(ticker)
            .build();
  }

  @Override
  public DistanceDuration getDistanceAndDuration(Location from, Location to) {
    Objects.requireNonNull(from, "from must not be null");
    Objects.requireNonNull(to, "to must not be null");
    String key = from.id().value() + "|" + to.id().value();
    return cache.get(key, ignored -> delegate.getDistanceAndDuration(from, to));
  }
}
