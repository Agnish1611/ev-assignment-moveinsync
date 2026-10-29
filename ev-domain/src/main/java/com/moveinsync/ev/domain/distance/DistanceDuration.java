package com.moveinsync.ev.domain.distance;

/**
 * Immutable value object holding distance in metres and duration in seconds between two locations.
 *
 * @param distanceMeters travel distance in metres (non-negative integer)
 * @param durationSeconds travel duration in seconds (non-negative integer)
 */
public record DistanceDuration(int distanceMeters, int durationSeconds) {

  public DistanceDuration {
    if (distanceMeters < 0) {
      throw new IllegalArgumentException("distanceMeters must be >= 0: " + distanceMeters);
    }
    if (durationSeconds < 0) {
      throw new IllegalArgumentException("durationSeconds must be >= 0: " + durationSeconds);
    }
  }

  public static DistanceDuration zero() {
    return new DistanceDuration(0, 0);
  }

  public static DistanceDuration of(int distanceMeters, int durationSeconds) {
    return new DistanceDuration(distanceMeters, durationSeconds);
  }
}
