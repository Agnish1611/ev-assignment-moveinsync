package com.moveinsync.ev.sim;

import java.time.Instant;
import java.util.Objects;
import java.util.OptionalLong;

/**
 * One injected cab observation. A silent cab has no reported SoC. An outlier reports an energy
 * figure the battery cannot hold. Noise stays inside the pack.
 */
public record CabReading(String cabId, Instant observedAt, OptionalLong reportedSocWh, Kind kind) {

  /** How this reading should be treated by a drift detector. */
  public enum Kind {
    NOISE,
    OUTLIER,
    SILENT
  }

  public CabReading {
    Objects.requireNonNull(cabId, "cabId must not be null");
    Objects.requireNonNull(observedAt, "observedAt must not be null");
    Objects.requireNonNull(reportedSocWh, "reportedSocWh must not be null");
    Objects.requireNonNull(kind, "kind must not be null");
    if (cabId.isBlank() || cabId.contains("@") || cabId.startsWith("+")) {
      throw new IllegalArgumentException("cabId must be an opaque identifier");
    }
    if (kind == Kind.SILENT && reportedSocWh.isPresent()) {
      throw new IllegalArgumentException("a silent cab has no reported SoC");
    }
    if (kind != Kind.SILENT && reportedSocWh.isEmpty()) {
      throw new IllegalArgumentException("a reported reading needs a SoC");
    }
    if (reportedSocWh.isPresent() && reportedSocWh.getAsLong() < 0L) {
      throw new IllegalArgumentException("reportedSocWh must be non-negative");
    }
  }
}
