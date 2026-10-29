package com.moveinsync.ev.domain.consumption;

import java.time.Instant;

/**
 * Provider interface for time-dependent traffic consumption multipliers.
 *
 * <p>Traffic factors are represented in integer basis points, where 10,000 basis points equals a
 * nominal 1.0000x multiplier. Values greater than 10,000 represent traffic congestion; values less
 * than 10,000 represent free-flow conditions.
 */
public interface TrafficFactorProvider {

  /** Standard scale factor representing 1.0000x in integer basis points. */
  int BASIS_POINTS_SCALE = 10_000;

  /**
   * Returns the traffic multiplier in integer basis points active at the specified instant.
   *
   * @param instant the time of travel in UTC
   * @return traffic factor in basis points (e.g. 12,500 = 1.25x)
   */
  int getTrafficFactorBasisPoints(Instant instant);
}
