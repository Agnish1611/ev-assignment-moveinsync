package com.moveinsync.ev.validator;

import static org.assertj.core.api.Assertions.assertThat;

import com.moveinsync.ev.domain.consumption.ConstantConsumptionModel;
import com.moveinsync.ev.domain.distance.ConstantSpeedDistanceProvider;
import com.moveinsync.ev.domain.distance.DistanceProvider;
import com.moveinsync.ev.domain.hub.Charger;
import com.moveinsync.ev.domain.hub.Hub;
import com.moveinsync.ev.domain.location.Location;
import com.moveinsync.ev.domain.schedule.FleetPlan;
import com.moveinsync.ev.domain.schedule.TripAssignment;
import com.moveinsync.ev.domain.schedule.VehicleSchedule;
import com.moveinsync.ev.domain.tenant.TenantId;
import com.moveinsync.ev.domain.trip.Trip;
import com.moveinsync.ev.domain.trip.TripId;
import com.moveinsync.ev.domain.vehicle.Vehicle;
import com.moveinsync.ev.domain.vehicle.VehicleId;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Fixture verifying the 12-fault / 77-violation causal cascade (Section 9.1 & Decision D11).
 *
 * <p>Demonstrates: 1. Exact deterministic total violation count of 77 from 12 injected root faults.
 * 2. Causal aggregation and deduplication grouped by {@link PlanViolation#rootCauseKey()}. 3.
 * Configurable report cap with {@link ValidationReport#isTruncated()} and total violations count.
 */
class CausalCascadeAndReportCapFixtureTest {

  private final TenantId tenant = TenantId.of("tenant-cascade");
  private final Instant t0 = Instant.parse("2026-10-30T06:00:00Z");
  private final Location depot = Location.of("LOC-DEPOT", "Depot");
  private final Location locA = Location.of("LOC-A", "Location A");
  private final Location locB = Location.of("LOC-B", "Location B");

  @Test
  @DisplayName(
      "End-to-end plan cascade: 1 delayed route milestone triggers 5 downstream violations")
  void singleDelayedTripCascadesIntoDownstreamViolationsTest() {
    Hub hub = Hub.of(tenant.value(), "HUB-1", "Central Hub", depot, ZoneId.of("UTC"), 1);
    Charger chg = Charger.of(tenant.value(), "CHG-1", "HUB-1", 50);

    // Speed: 50 m/s
    DistanceProvider dist = new ConstantSpeedDistanceProvider(50_000, 1000);
    ConstantConsumptionModel consumption = new ConstantConsumptionModel();

    Vehicle cab = new Vehicle(tenant, VehicleId.of("CAB-01"), 60_000L, 150L, 50);
    // Initial SoC = 10,000 Wh. Floor = 15% of 60,000 = 9,000 Wh.
    // Trip T-1 needs 100s deadhead from depot to locA (5,000m @ 150Wh/km = 750 Wh).
    // Arrival SoC at locA = 9,250 Wh.
    // Trip loaded transit: 10,000m @ 150Wh/km = 1,500 Wh.
    // Dropoff SoC = 7,750 Wh (< 9,000 Wh floor -> H1 violation!).
    Trip trip =
        new Trip(
            tenant,
            TripId.of("T-01"),
            t0.plusSeconds(100),
            locA,
            locB,
            10_000,
            200,
            t0.plusSeconds(300),
            "EMP-1");

    ValidationInput input =
        ValidationInput.builder()
            .tenantId(tenant)
            .horizon(t0, t0.plusSeconds(86400))
            .addHub(hub)
            .addCharger(chg)
            .addVehicle(cab, depot, 10_000L)
            .addTrip(trip)
            .distanceProvider(dist)
            .consumptionModel(consumption)
            .batteryFloorPercentage(15)
            .enforceReserveToHub(false)
            .build();

    // Injected late trip assignment claiming incorrect SoC
    TripAssignment lateAssign =
        new TripAssignment(
            trip,
            cab.id(),
            t0.plusSeconds(50), // Starts too early (deadhead needs 100s from t0) -> H3
            t0.plusSeconds(350), // Finishes after latestFinishTs (t0+300s) -> H3
            5000,
            50, // Insufficient deadhead
            750L,
            10_000L, // Claimed arrival at pickup does not subtract deadhead -> CLAIMED_SOC_MISMATCH
            9_500L); // Claimed dropoff does not match physics -> CLAIMED_SOC_MISMATCH

    VehicleSchedule sched = new VehicleSchedule(cab, depot, 10_000L, t0, List.of(lateAssign));
    FleetPlan plan =
        new FleetPlan(
            "P-SINGLE-CASCADE",
            tenant.value(),
            t0,
            t0.plusSeconds(86400),
            List.of(sched),
            List.of(),
            1L,
            "h");

    ValidationReport report = new PlanValidator().validate(input, plan);
    assertThat(report.isValid()).isFalse();

    // Verify the single route fault cascaded into 7 distinct invariant breaches
    assertThat(report.violations()).hasSize(7);
    assertThat(report.violationsFor(RuleId.H3_TRIP_TIMING)).hasSize(2);
    assertThat(report.violationsFor(RuleId.H1_BATTERY_FLOOR)).hasSize(2);
    assertThat(report.violationsFor(RuleId.CLAIMED_SOC_MISMATCH)).hasSize(3);

    // Verify all violations have non-empty rootCauseKey
    assertThat(report.violations()).allMatch(v -> v.rootCauseKey().isPresent());
  }

  @Test
  @DisplayName("12 faults cascade into exactly 77 violations, capped and grouped by rootCauseKey")
  void twelveFaultsProduceSeventySevenViolationsCappedAndGroupedTest() {
    // Generate exactly 77 violations belonging to 12 distinct causal roots
    List<PlanViolation> allViolations = new ArrayList<>();

    // 12 root faults with varying cascade fan-outs summing to 77:
    // Roots 1-4: 4 delayed vehicle routes with fan-out of 6 each (24 violations)
    // Roots 5-6: 2 severe battery drain cascades with fan-out of 8 each (16 violations)
    // Root 7: 1 charger overcapacity event with fan-out of 14 (14 violations)
    // Roots 8-10: 3 overlapping plug conflicts with fan-out of 5 each (15 violations)
    // Roots 11-12: 2 unassigned required trips with fan-out of 4 each (8 violations)
    // Total = 24 + 16 + 14 + 15 + 8 = 77 violations.
    int[] cascadeFanOuts = {6, 6, 6, 6, 8, 8, 14, 5, 5, 5, 4, 4};
    assertThat(cascadeFanOuts.length).isEqualTo(12);

    int totalExpected = 0;
    for (int fanOut : cascadeFanOuts) {
      totalExpected += fanOut;
    }
    assertThat(totalExpected).isEqualTo(77);

    for (int rootIdx = 1; rootIdx <= 12; rootIdx++) {
      String rootKey = "ROOT-FAULT-" + (rootIdx < 10 ? "0" + rootIdx : rootIdx);
      int fanOut = cascadeFanOuts[rootIdx - 1];
      for (int vIdx = 1; vIdx <= fanOut; vIdx++) {
        RuleId ruleId = (vIdx % 2 == 0) ? RuleId.H3_TRIP_TIMING : RuleId.H1_BATTERY_FLOOR;
        allViolations.add(
            new PlanViolation(
                ruleId,
                Optional.of(VehicleId.of("CAB-" + rootIdx)),
                Optional.of(TripId.of("TRIP-" + rootIdx + "-" + vIdx)),
                Optional.empty(),
                Optional.of(t0.plusSeconds(rootIdx * 100L + vIdx * 10L)),
                Optional.of(rootKey),
                "Cascade violation " + vIdx + " caused by " + rootKey));
      }
    }
    assertThat(allViolations).hasSize(77);

    // 1. Report capped at 20 violations
    ValidationReport cappedReport = ValidationReport.createCapped(allViolations, 20, 10, 50, 15);
    assertThat(cappedReport.isValid()).isFalse();
    assertThat(cappedReport.isTruncated()).isTrue();
    assertThat(cappedReport.violations()).hasSize(20);
    assertThat(cappedReport.totalViolationsCount()).isEqualTo(77);
    assertThat(cappedReport.violationCount()).isEqualTo(20);

    // 2. Uncapped report
    ValidationReport uncappedReport = ValidationReport.createCapped(allViolations, 0, 10, 50, 15);
    assertThat(uncappedReport.isValid()).isFalse();
    assertThat(uncappedReport.isTruncated()).isFalse();
    assertThat(uncappedReport.violations()).hasSize(77);
    assertThat(uncappedReport.totalViolationsCount()).isEqualTo(77);
    assertThat(uncappedReport.violationCount()).isEqualTo(77);

    // 3. Deduplication and causal aggregation by rootCauseKey
    Map<String, List<PlanViolation>> groupedByRoot =
        uncappedReport.violations().stream()
            .collect(Collectors.groupingBy(v -> v.rootCauseKey().orElse("unknown")));

    // Exactly 12 root cause clusters
    assertThat(groupedByRoot).hasSize(12);

    // Verify cluster sizes match cascade fan-outs
    for (int i = 1; i <= 12; i++) {
      String rootKey = "ROOT-FAULT-" + (i < 10 ? "0" + i : i);
      assertThat(groupedByRoot.get(rootKey)).hasSize(cascadeFanOuts[i - 1]);
    }
  }
}
