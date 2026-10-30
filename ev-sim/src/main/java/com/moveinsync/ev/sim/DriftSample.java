package com.moveinsync.ev.sim;

import java.util.List;
import java.util.Objects;

/**
 * Traffic multiplier and per-cab readings produced from one seed.
 *
 * @param trafficFactorBasisPoints 10_000 means nominal traffic
 * @param readings one row per cab, in cab-id order
 */
public record DriftSample(int trafficFactorBasisPoints, List<CabReading> readings) {

  public DriftSample {
    if (trafficFactorBasisPoints <= 0) {
      throw new IllegalArgumentException(
          "trafficFactorBasisPoints must be positive: " + trafficFactorBasisPoints);
    }
    Objects.requireNonNull(readings, "readings must not be null");
    readings = List.copyOf(readings);
    if (readings.isEmpty()) {
      throw new IllegalArgumentException("readings must not be empty");
    }
    int silent = 0;
    int outlier = 0;
    for (CabReading reading : readings) {
      Objects.requireNonNull(reading, "reading must not be null");
      if (reading.kind() == CabReading.Kind.SILENT) {
        silent++;
      } else if (reading.kind() == CabReading.Kind.OUTLIER) {
        outlier++;
      }
    }
    if (silent != 1 || outlier != 1) {
      throw new IllegalArgumentException("a sample needs one silent cab and one outlier");
    }
  }
}
