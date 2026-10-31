package com.moveinsync.ev.service.api;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** How long a solve may hold a job, and how often an expired lease is returned. */
@ConfigurationProperties(prefix = "ev.job")
public record JobSettings(
    @DefaultValue("120") int leaseSeconds, @DefaultValue("5000") long reapMillis) {

  public JobSettings {
    if (leaseSeconds <= 0 || reapMillis <= 0L) {
      throw new IllegalArgumentException("job timings must be positive");
    }
  }
}
