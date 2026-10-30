package com.moveinsync.ev.service.cache;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Sizes and lifetimes for the read caches. Defaults match the staleness table. */
@ConfigurationProperties(prefix = "ev.cache")
public record CacheSettings(
    @DefaultValue("1000") int versionEntries,
    @DefaultValue("500") int currentEntries,
    @DefaultValue("500") int fleetEntries,
    @DefaultValue("10000") int distanceEntries,
    @DefaultValue("2") int currentTtlSeconds,
    @DefaultValue("60") int fleetTtlSeconds,
    @DefaultValue("24") int distanceTtlHours) {

  public CacheSettings {
    if (versionEntries < 1 || currentEntries < 1 || fleetEntries < 1 || distanceEntries < 1) {
      throw new IllegalArgumentException("cache size must be positive");
    }
    if (currentTtlSeconds < 1 || fleetTtlSeconds < 1 || distanceTtlHours < 1) {
      throw new IllegalArgumentException("cache lifetime must be positive");
    }
  }
}
