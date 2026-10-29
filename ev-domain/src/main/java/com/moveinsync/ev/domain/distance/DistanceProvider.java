package com.moveinsync.ev.domain.distance;

import com.moveinsync.ev.domain.location.Location;

/**
 * Polymorphic provider interface for distance and duration lookups between locations.
 *
 * <p>All quantities are integer values (metres and seconds).
 */
public interface DistanceProvider {

  /**
   * Resolves the travel distance and nominal duration between two locations.
   *
   * @param from origin location
   * @param to destination location
   * @return distance in metres and duration in seconds
   */
  DistanceDuration getDistanceAndDuration(Location from, Location to);
}
