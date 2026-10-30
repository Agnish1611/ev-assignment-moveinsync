package com.moveinsync.ev.service.api;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Limits for the HTTP API. Defaults are the documented operating point. */
@ConfigurationProperties(prefix = "ev.api")
public record ApiSettings(
    @DefaultValue("60") int rateCapacity,
    @DefaultValue("60") int rateWindowSeconds,
    @DefaultValue("8192") int maxBodyBytes,
    @DefaultValue("64") int maxIdLength,
    @DefaultValue("500") int maxPowerKw,
    @DefaultValue("64") int maxChargerCount) {

  public ApiSettings {
    if (rateCapacity <= 0 || rateWindowSeconds <= 0 || maxBodyBytes <= 0 || maxIdLength <= 0) {
      throw new IllegalArgumentException("api limits must be positive");
    }
    if (maxPowerKw <= 0 || maxChargerCount <= 0) {
      throw new IllegalArgumentException("capacity limits must be positive");
    }
  }
}
