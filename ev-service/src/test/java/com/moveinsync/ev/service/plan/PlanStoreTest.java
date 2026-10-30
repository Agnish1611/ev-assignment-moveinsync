package com.moveinsync.ev.service.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.moveinsync.ev.domain.hub.ChargerId;
import com.moveinsync.ev.domain.hub.HubId;
import com.moveinsync.ev.domain.location.Location;
import com.moveinsync.ev.domain.location.LocationId;
import com.moveinsync.ev.domain.schedule.ChargingSession;
import com.moveinsync.ev.domain.schedule.FleetPlan;
import com.moveinsync.ev.domain.schedule.TripAssignment;
import com.moveinsync.ev.domain.schedule.UnassignedReasonCode;
import com.moveinsync.ev.domain.schedule.UnassignedTrip;
import com.moveinsync.ev.domain.schedule.VehicleSchedule;
import com.moveinsync.ev.domain.tenant.TenantId;
import com.moveinsync.ev.domain.trip.Trip;
import com.moveinsync.ev.domain.trip.TripId;
import com.moveinsync.ev.domain.vehicle.Vehicle;
import com.moveinsync.ev.domain.vehicle.VehicleId;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;

/** Snapshot bytes, version cadence, and trip diffs. No database. */
class PlanStoreTest {

  private static final Instant START = Instant.parse("2026-10-30T08:00:00Z");
  private static final TenantId TENANT = TenantId.of("tenant-a");

  @Test
  void aSnapshotRoundTripsThePlan() {
    FleetPlan plan = sample();

    FleetPlan restored = PlanDocuments.gunzip(PlanDocuments.gzip(plan));

    assertThat(restored).usingRecursiveComparison().isEqualTo(plan);
  }

  @Test
  void aSnapshotOfAnotherFormatIsRejected() throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (GZIPOutputStream gzip = new GZIPOutputStream(bytes);
        DataOutputStream out = new DataOutputStream(gzip)) {
      out.writeInt(2);
    }

    assertThatThrownBy(() -> PlanDocuments.gunzip(bytes.toByteArray()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("format");
  }

  @Test
  void snapshotsAreDueOnTheFirstVersionEveryFiftiethAndAfterTenMinutes() {
    Instant now = START;
    assertThat(SnapshotPolicy.due(1L, Optional.empty(), now)).isTrue();
    assertThat(SnapshotPolicy.due(2L, Optional.of(now), now)).isFalse();
    assertThat(SnapshotPolicy.due(2L, Optional.empty(), now)).isTrue();
    assertThat(SnapshotPolicy.due(50L, Optional.of(now), now)).isTrue();
    assertThat(SnapshotPolicy.due(2L, Optional.of(now), now.plusSeconds(600))).isTrue();
    assertThatThrownBy(() -> SnapshotPolicy.due(0L, Optional.empty(), now))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aDiffListsOnlyTheTripsThatMoved() {
    String changes =
        PlanDiffs.between(Map.of("TRIP-A", "CAB-1", "TRIP-B", "CAB-2"), Map.of("TRIP-B", "CAB-3"));

    assertThat(changes).isEqualTo("TRIP-A\nCAB-1\n\nTRIP-B\nCAB-2\nCAB-3\n");
    assertThat(PlanDiffs.between(Map.of("TRIP-A", "CAB-1"), Map.of("TRIP-A", "CAB-1"))).isEmpty();
  }

  private static FleetPlan sample() {
    Vehicle vehicle = new Vehicle(TENANT, VehicleId.of("CAB-1"), 60_000L, 150L, 50);
    Location depot = new Location(LocationId.of("LOC-HUB"), "Hub", 1, 2);
    Trip served = trip("TRIP-1", 3_600);
    Trip parked = trip("TRIP-2", 7_200);
    TripAssignment assignment =
        new TripAssignment(
            served,
            vehicle.id(),
            served.startTs(),
            served.plannedDropoffTs(),
            0,
            0,
            0L,
            20_000L,
            18_000L);
    ChargingSession session =
        new ChargingSession(
            "SESS-1",
            vehicle.id(),
            HubId.of("HUB-1"),
            ChargerId.of("CHG-1"),
            START.plusSeconds(10),
            START.plusSeconds(400),
            12_000L,
            20_000L,
            0L);
    VehicleSchedule schedule =
        new VehicleSchedule(vehicle, depot, 12_000L, START, List.of(session, assignment));
    return new FleetPlan(
        "plan-1",
        TENANT.value(),
        START,
        START.plusSeconds(28_800),
        List.of(schedule),
        List.of(UnassignedTrip.of(parked, UnassignedReasonCode.OTHER, "backup")),
        11L,
        "hash-1");
  }

  private static Trip trip(String id, long offsetSeconds) {
    Instant pickup = START.plusSeconds(offsetSeconds);
    return new Trip(
        TENANT,
        TripId.of(id),
        pickup,
        new Location(LocationId.of("LOC-P"), "Pickup", 3, 4),
        new Location(LocationId.of("LOC-D"), "Drop", 5, 6),
        10_000,
        1_200,
        pickup.plusSeconds(3_600),
        "EMP-1");
  }
}
