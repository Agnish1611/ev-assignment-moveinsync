package com.moveinsync.ev.service.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.benmanes.caffeine.cache.Ticker;
import com.moveinsync.ev.domain.distance.DistanceDuration;
import com.moveinsync.ev.domain.distance.DistanceProvider;
import com.moveinsync.ev.domain.location.Location;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** The same location ids are one cache entry. Coordinates are not part of the key. */
class DistanceCacheTest {

  @Test
  void aRepeatedPairDoesNotCallTheDelegateUntilTheLifetimeEnds() {
    AtomicInteger calls = new AtomicInteger();
    DistanceProvider delegate =
        (from, to) -> {
          calls.incrementAndGet();
          return new DistanceDuration(5000, 500);
        };
    ManualTicker ticker = new ManualTicker();
    CacheSettings settings = new CacheSettings(8, 8, 8, 8, 2, 60, 24);
    DistanceCache cache = new DistanceCache(delegate, settings, ticker);
    Location origin = Location.of("HUB-1", "hub", 1, 2);
    Location destination = Location.of("OFFICE-1", "office", 3, 4);

    assertThat(cache.getDistanceAndDuration(origin, destination))
        .isEqualTo(new DistanceDuration(5000, 500));
    assertThat(
            cache.getDistanceAndDuration(
                Location.of("HUB-1", "hub", 9, 8), Location.of("OFFICE-1", "office", 7, 6)))
        .isEqualTo(new DistanceDuration(5000, 500));
    assertThat(cache.getDistanceAndDuration(origin, Location.of("OFFICE-2", "other", 1, 1)))
        .isEqualTo(new DistanceDuration(5000, 500));
    assertThat(calls).hasValue(2);

    ticker.advance(Duration.ofDays(1).plusSeconds(1));
    cache.getDistanceAndDuration(origin, destination);
    assertThat(calls).hasValue(3);
  }

  @Test
  void aFailedLookupIsTriedAgain() {
    AtomicInteger calls = new AtomicInteger();
    DistanceProvider delegate =
        (from, to) -> {
          if (calls.incrementAndGet() == 1) {
            throw new IllegalStateException("down");
          }
          return new DistanceDuration(1, 1);
        };
    DistanceCache cache =
        new DistanceCache(delegate, new CacheSettings(4, 4, 4, 4, 2, 60, 24), new ManualTicker());

    assertThatThrownBy(() -> cache.getDistanceAndDuration(Location.of("A"), Location.of("B")))
        .isInstanceOf(IllegalStateException.class);
    assertThat(cache.getDistanceAndDuration(Location.of("A"), Location.of("B")))
        .isEqualTo(new DistanceDuration(1, 1));
    assertThat(calls).hasValue(2);
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
