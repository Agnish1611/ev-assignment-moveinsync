package com.moveinsync.ev.domain.schedule;

import com.moveinsync.ev.domain.hub.ChargerId;
import com.moveinsync.ev.domain.hub.HubId;
import com.moveinsync.ev.domain.vehicle.VehicleId;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Immutable domain representation of a scheduled or recorded EV charging session at a hub.
 *
 * @param sessionId unique session identifier
 * @param vehicleId the vehicle being charged
 * @param hubId the hub where the session occurs
 * @param chargerId the specific charger plug assigned
 * @param startTime session start instant in UTC
 * @param endTime session end instant in UTC
 * @param startSocWh vehicle battery SoC at start in Wh
 * @param targetSocWh vehicle battery SoC at end in Wh
 * @param costMicros electricity cost in integer micro-units
 */
public record ChargingSession(
    String sessionId,
    VehicleId vehicleId,
    HubId hubId,
    ChargerId chargerId,
    Instant startTime,
    Instant endTime,
    long startSocWh,
    long targetSocWh,
    long costMicros)
    implements RouteActivity {

  public ChargingSession {
    Objects.requireNonNull(sessionId, "sessionId must not be null");
    Objects.requireNonNull(vehicleId, "vehicleId must not be null");
    Objects.requireNonNull(hubId, "hubId must not be null");
    Objects.requireNonNull(chargerId, "chargerId must not be null");
    Objects.requireNonNull(startTime, "startTime must not be null");
    Objects.requireNonNull(endTime, "endTime must not be null");
  }

  @Override
  public Instant activityStartTime() {
    return startTime;
  }

  @Override
  public Instant activityEndTime() {
    return endTime;
  }

  /**
   * Calculates the session duration in seconds.
   *
   * @return duration in seconds
   */
  public long durationSeconds() {
    return Duration.between(startTime, endTime).getSeconds();
  }

  /**
   * Calculates the net energy delivered to the battery in Watt-hours.
   *
   * @return energy delivered in Wh
   */
  public long energyDeliveredWh() {
    return targetSocWh - startSocWh;
  }

  /**
   * Checks whether this charging session overlaps in time with another session.
   *
   * @param other the other session
   * @return true if the intervals [start, end) overlap
   */
  public boolean overlaps(ChargingSession other) {
    Objects.requireNonNull(other, "other ChargingSession must not be null");
    return startTime.isBefore(other.endTime) && endTime.isAfter(other.startTime);
  }

  /**
   * Checks whether this session overlaps with a given time interval.
   *
   * @param intervalStart interval start instant
   * @param intervalEnd interval end instant
   * @return true if overlapping
   */
  public boolean overlaps(Instant intervalStart, Instant intervalEnd) {
    Objects.requireNonNull(intervalStart, "intervalStart must not be null");
    Objects.requireNonNull(intervalEnd, "intervalEnd must not be null");
    return startTime.isBefore(intervalEnd) && endTime.isAfter(intervalStart);
  }
}
