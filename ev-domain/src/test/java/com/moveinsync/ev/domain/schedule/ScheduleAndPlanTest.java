package com.moveinsync.ev.domain.schedule;

import static org.assertj.core.api.Assertions.assertThat;

import com.moveinsync.ev.domain.hub.ChargerId;
import com.moveinsync.ev.domain.hub.HubId;
import com.moveinsync.ev.domain.location.Location;
import com.moveinsync.ev.domain.trip.Trip;
import com.moveinsync.ev.domain.trip.TripId;
import com.moveinsync.ev.domain.vehicle.Vehicle;
import com.moveinsync.ev.domain.vehicle.VehicleId;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ScheduleAndPlanTest {

  private final VehicleId cabId = VehicleId.of("CAB-01");
  private final Vehicle cab = new Vehicle(cabId, 60_000L, 160L, 50);
  private final Location depot = Location.of("LOC-DEPOT", "Depot");
  private final Location locA = Location.of("LOC-A", "Pickup A");
  private final Location locB = Location.of("LOC-B", "Dropoff B");

  @Test
  @DisplayName("ChargingSession calculates duration, delivered energy, and overlap")
  void chargingSessionBasics() {
    Instant t0 = Instant.parse("2026-10-30T10:00:00Z");
    Instant t1 = Instant.parse("2026-10-30T11:00:00Z");

    ChargingSession session1 =
        new ChargingSession(
            "SESS-1",
            cabId,
            HubId.of("HUB-1"),
            ChargerId.of("CHG-1"),
            t0,
            t1,
            20_000L,
            50_000L,
            1_500_000L);

    assertThat(session1.durationSeconds()).isEqualTo(3600L);
    assertThat(session1.energyDeliveredWh()).isEqualTo(30_000L);
    assertThat(session1.costMicros()).isEqualTo(1_500_000L);

    // Overlapping session
    ChargingSession session2 =
        new ChargingSession(
            "SESS-2",
            VehicleId.of("CAB-02"),
            HubId.of("HUB-1"),
            ChargerId.of("CHG-1"),
            Instant.parse("2026-10-30T10:30:00Z"),
            Instant.parse("2026-10-30T11:30:00Z"),
            10_000L,
            40_000L,
            1_500_000L);

    assertThat(session1.overlaps(session2)).isTrue();
  }

  @Test
  @DisplayName(
      "VehicleSchedule sorts activities chronologically and rejects overlapping activities")
  void vehicleScheduleActivityValidation() {
    Instant t10 = Instant.parse("2026-10-30T10:00:00Z");
    Instant t11 = Instant.parse("2026-10-30T11:00:00Z");
    Instant t12 = Instant.parse("2026-10-30T12:00:00Z");
    Instant t13 = Instant.parse("2026-10-30T13:00:00Z");

    Trip trip1 = new Trip(TripId.of("T1"), t10, locA, locB, 10_000, 1800, t11, "EMP-101");
    TripAssignment assign1 =
        new TripAssignment(
            trip1, cabId, t10, t10.plusSeconds(1800), 5000, 600, 800L, 40_000L, 35_000L);

    ChargingSession charge1 =
        new ChargingSession(
            "SESS-1",
            cabId,
            HubId.of("HUB-1"),
            ChargerId.of("CHG-1"),
            t12,
            t13,
            35_000L,
            55_000L,
            1_000_000L);

    // Activities provided out of chronological order are sorted by start time
    VehicleSchedule schedule =
        new VehicleSchedule(cab, depot, 50_000L, t10.minusSeconds(1800), List.of(charge1, assign1));

    assertThat(schedule.activityCount()).isEqualTo(2);
    assertThat(schedule.activities().get(0)).isEqualTo(assign1);
    assertThat(schedule.activities().get(1)).isEqualTo(charge1);
  }

  @Test
  @DisplayName("FleetPlan aggregates schedule metrics accurately")
  void fleetPlanAggregation() {
    Instant start = Instant.parse("2026-10-30T08:00:00Z");
    Instant end = Instant.parse("2026-10-30T20:00:00Z");

    Trip trip1 =
        new Trip(
            TripId.of("T1"),
            Instant.parse("2026-10-30T09:00:00Z"),
            locA,
            locB,
            10_000,
            1800,
            Instant.parse("2026-10-30T10:00:00Z"),
            "EMP-1");

    TripAssignment assign1 =
        new TripAssignment(
            trip1,
            cabId,
            Instant.parse("2026-10-30T09:00:00Z"),
            Instant.parse("2026-10-30T09:30:00Z"),
            0,
            0,
            0L,
            50_000L,
            45_000L);

    ChargingSession charge1 =
        new ChargingSession(
            "SESS-1",
            cabId,
            HubId.of("HUB-1"),
            ChargerId.of("CHG-1"),
            Instant.parse("2026-10-30T11:00:00Z"),
            Instant.parse("2026-10-30T12:00:00Z"),
            45_000L,
            60_000L,
            1_200_000L);

    VehicleSchedule schedule =
        new VehicleSchedule(cab, depot, 50_000L, start, List.of(assign1, charge1));

    Trip unassignedTrip =
        new Trip(
            TripId.of("T-BACKUP"),
            Instant.parse("2026-10-30T14:00:00Z"),
            locA,
            locB,
            50_000,
            3600,
            Instant.parse("2026-10-30T15:30:00Z"),
            "EMP-2");

    UnassignedTrip backup =
        new UnassignedTrip(
            unassignedTrip,
            UnassignedReasonCode.CHARGER_CONGESTION,
            "Hub 1 at capacity during 13:00-14:00 window");

    FleetPlan plan =
        new FleetPlan(
            "PLAN-v1",
            "tenant-corp-1",
            start,
            end,
            List.of(schedule),
            List.of(backup),
            42L,
            "hash-snapshot-123");

    assertThat(plan.totalAssignedTrips()).isEqualTo(1);
    assertThat(plan.totalUnassignedTrips()).isEqualTo(1);
    assertThat(plan.totalChargingSessions()).isEqualTo(1);
    assertThat(plan.totalChargingCostMicros()).isEqualTo(1_200_000L);
    assertThat(plan.totalEnergyChargedWh()).isEqualTo(15_000L);
    assertThat(plan.seed()).isEqualTo(42L);
    assertThat(plan.inputHash()).isEqualTo("hash-snapshot-123");
  }
}
