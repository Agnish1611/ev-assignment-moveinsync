package com.moveinsync.ev.domain.schedule;

import com.moveinsync.ev.domain.vehicle.VehicleId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable top-level domain model representing a complete fleet plan for a shift horizon.
 *
 * <p>Stores the deterministic solver seed and input hash to guarantee reproducibility at 3 AM.
 *
 * @param planId unique identifier or version label for the plan
 * @param tenantId tenant identifier
 * @param horizonStart start of the planning window in UTC
 * @param horizonEnd end of the planning window in UTC
 * @param vehicleSchedules schedules assigned to fleet cabs
 * @param unassignedTrips trips routed to backup capacity
 * @param seed random number generator seed used by the planner
 * @param inputHash cryptographic or deterministic hash of the solver input snapshot
 */
public record FleetPlan(
    String planId,
    String tenantId,
    Instant horizonStart,
    Instant horizonEnd,
    List<VehicleSchedule> vehicleSchedules,
    List<UnassignedTrip> unassignedTrips,
    long seed,
    String inputHash) {

  public FleetPlan {
    Objects.requireNonNull(planId, "planId must not be null");
    Objects.requireNonNull(tenantId, "tenantId must not be null");
    Objects.requireNonNull(horizonStart, "horizonStart must not be null");
    Objects.requireNonNull(horizonEnd, "horizonEnd must not be null");
    Objects.requireNonNull(vehicleSchedules, "vehicleSchedules must not be null");
    Objects.requireNonNull(unassignedTrips, "unassignedTrips must not be null");
    Objects.requireNonNull(inputHash, "inputHash must not be null");

    if (!horizonStart.isBefore(horizonEnd)) {
      throw new IllegalArgumentException(
          "horizonStart (" + horizonStart + ") must be before horizonEnd (" + horizonEnd + ")");
    }

    vehicleSchedules = Collections.unmodifiableList(new ArrayList<>(vehicleSchedules));
    unassignedTrips = Collections.unmodifiableList(new ArrayList<>(unassignedTrips));
  }

  public int totalAssignedTrips() {
    int count = 0;
    for (VehicleSchedule schedule : vehicleSchedules) {
      for (RouteActivity activity : schedule.activities()) {
        if (activity instanceof TripAssignment) {
          count++;
        }
      }
    }
    return count;
  }

  public int totalUnassignedTrips() {
    return unassignedTrips.size();
  }

  public int totalChargingSessions() {
    int count = 0;
    for (VehicleSchedule schedule : vehicleSchedules) {
      for (RouteActivity activity : schedule.activities()) {
        if (activity instanceof ChargingSession) {
          count++;
        }
      }
    }
    return count;
  }

  public long totalChargingCostMicros() {
    long cost = 0L;
    for (VehicleSchedule schedule : vehicleSchedules) {
      for (RouteActivity activity : schedule.activities()) {
        if (activity instanceof ChargingSession session) {
          cost = Math.addExact(cost, session.costMicros());
        }
      }
    }
    return cost;
  }

  public long totalEnergyChargedWh() {
    long energy = 0L;
    for (VehicleSchedule schedule : vehicleSchedules) {
      for (RouteActivity activity : schedule.activities()) {
        if (activity instanceof ChargingSession session) {
          energy = Math.addExact(energy, session.energyDeliveredWh());
        }
      }
    }
    return energy;
  }

  public Optional<VehicleSchedule> findVehicleSchedule(VehicleId vehicleId) {
    Objects.requireNonNull(vehicleId, "vehicleId must not be null");
    return vehicleSchedules.stream().filter(s -> s.vehicleId().equals(vehicleId)).findFirst();
  }
}
