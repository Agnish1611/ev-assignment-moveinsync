package com.moveinsync.ev.service.api;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/** One token bucket per user. Callers share the lock so the map stays consistent. */
public final class RateGate {

  private final ApiSettings settings;
  private final Map<String, Bucket> buckets = new HashMap<>();

  public RateGate(ApiSettings settings) {
    this.settings = settings;
  }

  public synchronized boolean allow(String userId) {
    Bucket bucket = buckets.computeIfAbsent(userId, ignored -> newBucket());
    return bucket.tryConsume(1);
  }

  private Bucket newBucket() {
    Bandwidth bandwidth =
        Bandwidth.builder()
            .capacity(settings.rateCapacity())
            .refillGreedy(settings.rateCapacity(), Duration.ofSeconds(settings.rateWindowSeconds()))
            .build();
    return Bucket.builder().addLimit(bandwidth).build();
  }
}
