package com.moveinsync.ev.domain.hub;

import com.moveinsync.ev.domain.location.Location;
import java.time.ZoneId;
import java.util.Objects;

/**
 * Immutable domain model representing a fleet charging hub.
 *
 * <p>Each hub operates in a specific local time zone, which governs its local tariff windows and
 * shift timing before internal conversion to UTC epoch seconds.
 *
 * @param id unique hub identifier
 * @param name non-PII operational name (e.g. "Electronic City Hub 1")
 * @param location location representation of the hub
 * @param timeZone local time zone for scheduling and local tariff windows
 * @param chargerCount total number of physical charger plugs at this hub
 */
public record Hub(HubId id, String name, Location location, ZoneId timeZone, int chargerCount) {

  public Hub {
    Objects.requireNonNull(id, "Hub id must not be null");
    Objects.requireNonNull(name, "Hub name must not be null");
    Objects.requireNonNull(location, "Hub location must not be null");
    Objects.requireNonNull(timeZone, "Hub timeZone must not be null");
    if (chargerCount <= 0) {
      throw new IllegalArgumentException("Hub charger count must be positive: " + chargerCount);
    }
  }

  public static Hub of(
      String id, String name, Location location, ZoneId timeZone, int chargerCount) {
    return new Hub(HubId.of(id), name, location, timeZone, chargerCount);
  }
}
