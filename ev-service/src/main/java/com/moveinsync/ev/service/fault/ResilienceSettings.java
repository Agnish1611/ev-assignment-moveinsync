package com.moveinsync.ev.service.fault;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Bounds for a database or broker that stops answering. The defaults are the operating point: a
 * connect waits two seconds, three failures open the gate for five seconds, and a paused consumer
 * loses its partitions after six seconds.
 */
@ConfigurationProperties(prefix = "ev.resilience")
public record ResilienceSettings(
    @DefaultValue("2") int connectTimeoutSeconds,
    @DefaultValue("2") int socketTimeoutSeconds,
    @DefaultValue("3") int failureLimit,
    @DefaultValue("5000") long openMillis,
    @DefaultValue("2") int retryAfterSeconds,
    @DefaultValue("6000") long sessionTimeoutMillis,
    @DefaultValue("1000") long heartbeatMillis) {

  private static final long MINIMUM_SESSION_MILLIS = 6_000L;

  public ResilienceSettings {
    if (connectTimeoutSeconds <= 0 || socketTimeoutSeconds <= 0 || failureLimit <= 0) {
      throw new IllegalArgumentException("resilience limits must be positive");
    }
    if (openMillis <= 0L || retryAfterSeconds <= 0) {
      throw new IllegalArgumentException("resilience delays must be positive");
    }
    if (sessionTimeoutMillis < MINIMUM_SESSION_MILLIS || heartbeatMillis <= 0L) {
      throw new IllegalArgumentException("session timeout is below the broker minimum");
    }
    if (heartbeatMillis * 3L >= sessionTimeoutMillis) {
      throw new IllegalArgumentException("heartbeat must stay under a third of the session");
    }
  }

  public static ResilienceSettings standard() {
    return new ResilienceSettings(2, 2, 3, 5_000L, 2, MINIMUM_SESSION_MILLIS, 1_000L);
  }

  public int requestTimeoutMillis() {
    return connectTimeoutSeconds * 1_000;
  }

  public int deliveryTimeoutMillis() {
    return requestTimeoutMillis() + socketTimeoutSeconds * 1_000;
  }
}
