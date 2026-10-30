package com.moveinsync.ev.domain.distance;

import com.moveinsync.ev.domain.location.Location;
import java.util.Objects;

/**
 * Distance provider estimating duration based on a nominal integer speed in metres per second.
 *
 * <p>Rounding is conservative: rounds duration UP to the next integer second.
 */
public final class ConstantSpeedDistanceProvider implements DistanceProvider {

  public static final int DEFAULT_SPEED_METRES_PER_SECOND = 10; // 36 km/h nominal urban speed

  private final int defaultDistanceMeters;
  private final int speedMetersPerSecond;

  public ConstantSpeedDistanceProvider(int defaultDistanceMeters, int speedMetersPerSecond) {
    if (defaultDistanceMeters < 0) {
      throw new IllegalArgumentException(
          "defaultDistanceMeters must be non-negative: " + defaultDistanceMeters);
    }
    if (speedMetersPerSecond <= 0) {
      throw new IllegalArgumentException(
          "speedMetersPerSecond must be strictly positive: " + speedMetersPerSecond);
    }
    this.defaultDistanceMeters = defaultDistanceMeters;
    this.speedMetersPerSecond = speedMetersPerSecond;
  }

  public int defaultDistanceMeters() {
    return defaultDistanceMeters;
  }

  public int speedMetersPerSecond() {
    return speedMetersPerSecond;
  }

  public ConstantSpeedDistanceProvider() {
    this(5_000, DEFAULT_SPEED_METRES_PER_SECOND);
  }

  @Override
  public DistanceDuration getDistanceAndDuration(Location from, Location to) {
    Objects.requireNonNull(from, "from location must not be null");
    Objects.requireNonNull(to, "to location must not be null");

    if (from.id().equals(to.id())) {
      return DistanceDuration.zero();
    }

    // Conservative integer rounding UP: (distance + speed - 1) / speed
    int durationSeconds = (defaultDistanceMeters + speedMetersPerSecond - 1) / speedMetersPerSecond;

    return new DistanceDuration(defaultDistanceMeters, durationSeconds);
  }
}
