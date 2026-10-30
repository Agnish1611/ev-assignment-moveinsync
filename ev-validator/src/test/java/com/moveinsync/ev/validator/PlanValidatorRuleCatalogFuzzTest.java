package com.moveinsync.ev.validator;

import static org.assertj.core.api.Assertions.assertThat;

import com.moveinsync.ev.domain.consumption.ConstantConsumptionModel;
import com.moveinsync.ev.domain.hub.ChargerId;
import com.moveinsync.ev.domain.hub.Hub;
import com.moveinsync.ev.domain.hub.HubId;
import com.moveinsync.ev.domain.schedule.ChargingSession;
import com.moveinsync.ev.domain.schedule.FleetPlan;
import com.moveinsync.ev.domain.schedule.TripAssignment;
import com.moveinsync.ev.domain.schedule.VehicleSchedule;
import com.moveinsync.ev.domain.tenant.TenantId;
import com.moveinsync.ev.domain.trip.Trip;
import com.moveinsync.ev.domain.trip.TripId;
import com.moveinsync.ev.domain.vehicle.Vehicle;
import com.moveinsync.ev.domain.vehicle.VehicleId;
import java.time.ZoneId;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Seeded corruption for every {@link RuleId}. Extra rules on the same plan are allowed. */
class PlanValidatorRuleCatalogFuzzTest {

  private static final int TRIALS = 8;

  @Test
  @DisplayName("Seeded fuzz: every RuleId is reported by a single-fault corruption")
  void everyRuleIdIsDetectedByASeededCorruption() {
    Random rng = new Random(20261030L);
    PlanValidator validator = new PlanValidator();
    for (int trial = 0; trial < TRIALS; trial++) {
      PlanValidatorFuzzFixture fixture = new PlanValidatorFuzzFixture(trial, rng);
      for (RuleId rule : RuleId.values()) {
        RuleProbe probe = probe(rule, fixture);
        ValidationReport report = validator.validate(probe.input(), probe.plan());
        assertThat(report.violationsFor(rule))
            .as("rule %s trial %d produced %s", rule, trial, report.violations())
            .isNotEmpty();
      }
    }
  }

  private static RuleProbe probe(RuleId rule, PlanValidatorFuzzFixture fixture) {
    return switch (rule) {
      case H1_BATTERY_FLOOR -> floorProbe(fixture);
      case H2_CHARGER_CAPACITY -> capacityProbe(fixture);
      case H3_TRIP_TIMING -> timingProbe(fixture);
      case H4_ASSIGNMENT_OVERLAP -> overlapProbe(fixture);
      case H4_DOUBLE_ASSIGNMENT -> doubleAssignProbe(fixture);
      case H5_RESERVE_TO_HUB -> reserveProbe(fixture);
      case H6_CHARGING_CURVE -> curveProbe(fixture);
      case TENANT_MISMATCH -> tenantProbe(fixture);
      case UNKNOWN_VEHICLE -> unknownVehicleProbe(fixture);
      case UNKNOWN_TRIP -> unknownTripProbe(fixture);
      case UNKNOWN_HUB -> unknownHubProbe(fixture);
      case UNKNOWN_CHARGER -> unknownChargerProbe(fixture);
      case CLAIMED_SOC_MISMATCH -> PlanValidatorOpsRuleProbes.claimedSocProbe(fixture);
      case CHARGER_UNAVAILABLE -> PlanValidatorOpsRuleProbes.unavailableProbe(fixture);
      case CHARGER_DOUBLE_BOOKED -> PlanValidatorOpsRuleProbes.doubleBookedProbe(fixture);
      case TRIP_OMITTED -> PlanValidatorOpsRuleProbes.omittedProbe(fixture);
      case TRIP_UNKNOWN -> PlanValidatorOpsRuleProbes.unknownUnassignedProbe(fixture);
      case TRIP_ASSIGNED_AND_UNASSIGNED -> PlanValidatorOpsRuleProbes.bothListsProbe(fixture);
      case COST_MISMATCH -> PlanValidatorOpsRuleProbes.costProbe(fixture);
      case SOC_OUT_OF_RANGE -> PlanValidatorOpsRuleProbes.socBoundsProbe(fixture);
      case ROUTE_UNAVAILABLE -> PlanValidatorOpsRuleProbes.routeProbe(fixture);
      case ACTIVITY_OUTSIDE_HORIZON -> PlanValidatorOpsRuleProbes.horizonProbe(fixture);
      case VALIDATOR_INTERNAL_ERROR -> new RuleProbe(fixture.input, null);
    };
  }

  private static RuleProbe floorProbe(PlanValidatorFuzzFixture fixture) {
    TripAssignment low =
        RuleProbeSupport.assignment(
            fixture, fixture.t0.plusSeconds(600), fixture.t0.plusSeconds(1200), 9_500L, 8_750L);
    VehicleSchedule schedule =
        new VehicleSchedule(fixture.cab, fixture.pickup, 9_500L, fixture.t0, List.of(low));
    return new RuleProbe(
        RuleProbeSupport.input(
            fixture, false, fixture.charger, RuleProbeSupport.distances(fixture), 9_500L),
        RuleProbeSupport.plan(fixture, List.of(schedule), List.of()));
  }

  private static RuleProbe capacityProbe(PlanValidatorFuzzFixture fixture) {
    Hub scarce = Hub.of("tenant-fuzz", "HUB-1", "H1", fixture.depot, ZoneId.of("UTC"), 1);
    ChargingSession second =
        RuleProbeSupport.copySession(
            fixture, "S-CAP", fixture.session.startSocWh(), fixture.session.targetSocWh());
    VehicleSchedule schedule =
        new VehicleSchedule(
            fixture.cab,
            fixture.pickup,
            fixture.startSoc,
            fixture.t0,
            List.of(fixture.session, second));
    ValidationInput input =
        ValidationInput.builder()
            .tenantId(fixture.tenant)
            .horizon(fixture.t0, fixture.t0.plusSeconds(86_400))
            .addHub(scarce)
            .addCharger(fixture.charger)
            .addVehicle(fixture.cab, fixture.pickup, fixture.startSoc)
            .addTrip(fixture.trip)
            .addTariff(RuleProbeSupport.tariff(fixture))
            .distanceProvider(RuleProbeSupport.distances(fixture))
            .consumptionModel(new ConstantConsumptionModel())
            .batteryFloorPercentage(15)
            .enforceReserveToHub(false)
            .build();
    return new RuleProbe(input, RuleProbeSupport.plan(fixture, List.of(schedule), List.of()));
  }

  private static RuleProbe timingProbe(PlanValidatorFuzzFixture fixture) {
    TripAssignment late =
        RuleProbeSupport.assignment(
            fixture, fixture.t0.plusSeconds(601), fixture.t0.plusSeconds(1201), 50_000L, 49_250L);
    return RuleProbeSupport.scheduleProbe(fixture, List.of(late));
  }

  private static RuleProbe overlapProbe(PlanValidatorFuzzFixture fixture) {
    Trip second =
        new Trip(
            fixture.tenant,
            TripId.of("TRIP-OV-" + fixture.trip.id().value()),
            fixture.t0.plusSeconds(1_199),
            fixture.pickup,
            fixture.dropoff,
            5_000,
            600,
            fixture.t0.plusSeconds(3_600),
            "EMP");
    TripAssignment first = fixture.assignment;
    TripAssignment overlapping =
        new TripAssignment(
            second,
            fixture.cab.id(),
            fixture.t0.plusSeconds(1_199),
            fixture.t0.plusSeconds(1_799),
            0,
            0,
            0L,
            49_250L,
            48_500L);
    VehicleSchedule schedule =
        new VehicleSchedule(
            fixture.cab, fixture.pickup, fixture.startSoc, fixture.t0, List.of(first, overlapping));
    ValidationInput input =
        ValidationInput.builder()
            .tenantId(fixture.tenant)
            .horizon(fixture.t0, fixture.t0.plusSeconds(86_400))
            .addHub(fixture.hub)
            .addCharger(fixture.charger)
            .addVehicle(fixture.cab, fixture.pickup, fixture.startSoc)
            .addTrip(fixture.trip)
            .addTrip(second)
            .addTariff(RuleProbeSupport.tariff(fixture))
            .distanceProvider(RuleProbeSupport.distances(fixture))
            .consumptionModel(new ConstantConsumptionModel())
            .batteryFloorPercentage(15)
            .enforceReserveToHub(false)
            .build();
    return new RuleProbe(input, RuleProbeSupport.plan(fixture, List.of(schedule), List.of()));
  }

  private static RuleProbe doubleAssignProbe(PlanValidatorFuzzFixture fixture) {
    Vehicle other = new Vehicle(fixture.tenant, VehicleId.of("CAB-02"), 60_000L, 150L, 50);
    VehicleSchedule second =
        new VehicleSchedule(
            other, fixture.pickup, fixture.startSoc, fixture.t0, List.of(fixture.assignment));
    ValidationInput input =
        ValidationInput.builder()
            .tenantId(fixture.tenant)
            .horizon(fixture.t0, fixture.t0.plusSeconds(86_400))
            .addHub(fixture.hub)
            .addCharger(fixture.charger)
            .addVehicle(fixture.cab, fixture.pickup, fixture.startSoc)
            .addVehicle(other, fixture.pickup, fixture.startSoc)
            .addTrip(fixture.trip)
            .addTariff(RuleProbeSupport.tariff(fixture))
            .distanceProvider(RuleProbeSupport.distances(fixture))
            .consumptionModel(new ConstantConsumptionModel())
            .batteryFloorPercentage(15)
            .enforceReserveToHub(false)
            .build();
    return new RuleProbe(
        input, RuleProbeSupport.plan(fixture, List.of(fixture.schedule, second), List.of()));
  }

  private static RuleProbe reserveProbe(PlanValidatorFuzzFixture fixture) {
    TripAssignment shortCharge =
        RuleProbeSupport.assignment(
            fixture, fixture.t0.plusSeconds(600), fixture.t0.plusSeconds(1200), 50_000L, 1_000L);
    VehicleSchedule schedule =
        new VehicleSchedule(
            fixture.cab, fixture.pickup, fixture.startSoc, fixture.t0, List.of(shortCharge));
    return new RuleProbe(
        RuleProbeSupport.input(
            fixture, true, fixture.charger, RuleProbeSupport.distances(fixture), fixture.startSoc),
        RuleProbeSupport.plan(fixture, List.of(schedule), List.of()));
  }

  private static RuleProbe curveProbe(PlanValidatorFuzzFixture fixture) {
    ChargingSession shortSession =
        RuleProbeSupport.copySession(fixture, fixture.session.sessionId(), 49_250L, 52_000L);
    ChargingSession shaved =
        new ChargingSession(
            shortSession.sessionId(),
            shortSession.vehicleId(),
            shortSession.hubId(),
            shortSession.chargerId(),
            shortSession.startTime(),
            shortSession.endTime().minusSeconds(1),
            shortSession.startSocWh(),
            shortSession.targetSocWh(),
            shortSession.costMicros());
    return RuleProbeSupport.scheduleProbe(fixture, List.of(shaved));
  }

  private static RuleProbe tenantProbe(PlanValidatorFuzzFixture fixture) {
    FleetPlan foreign =
        new FleetPlan(
            fixture.validPlan.planId(),
            TenantId.of("tenant-other"),
            fixture.t0,
            fixture.t0.plusSeconds(86_400),
            List.of(),
            List.of(),
            1L,
            "h");
    return new RuleProbe(fixture.input, foreign);
  }

  private static RuleProbe unknownVehicleProbe(PlanValidatorFuzzFixture fixture) {
    Vehicle ghost = new Vehicle(fixture.tenant, VehicleId.of("CAB-GHOST"), 60_000L, 150L, 50);
    VehicleSchedule schedule =
        new VehicleSchedule(ghost, fixture.pickup, fixture.startSoc, fixture.t0, List.of());
    return new RuleProbe(
        fixture.input, RuleProbeSupport.plan(fixture, List.of(schedule), List.of()));
  }

  private static RuleProbe unknownTripProbe(PlanValidatorFuzzFixture fixture) {
    Trip ghost =
        new Trip(
            fixture.tenant,
            TripId.of("TRIP-GHOST"),
            fixture.t0.plusSeconds(600),
            fixture.pickup,
            fixture.dropoff,
            5_000,
            600,
            fixture.t0.plusSeconds(3_600),
            "EMP");
    TripAssignment assign =
        new TripAssignment(
            ghost,
            fixture.cab.id(),
            ghost.startTs(),
            ghost.startTs().plusSeconds(600),
            0,
            0,
            0L,
            50_000L,
            49_250L);
    return RuleProbeSupport.scheduleProbe(fixture, List.of(assign));
  }

  private static RuleProbe unknownHubProbe(PlanValidatorFuzzFixture fixture) {
    ChargingSession session =
        new ChargingSession(
            "S-HUB",
            fixture.cab.id(),
            HubId.of("HUB-GHOST"),
            fixture.charger.id(),
            fixture.t0.plusSeconds(1_800),
            fixture.t0.plusSeconds(2_196),
            49_250L,
            52_000L,
            275_000L);
    return RuleProbeSupport.scheduleProbe(fixture, List.of(session));
  }

  private static RuleProbe unknownChargerProbe(PlanValidatorFuzzFixture fixture) {
    ChargingSession session =
        new ChargingSession(
            "S-CHG",
            fixture.cab.id(),
            fixture.hub.id(),
            ChargerId.of("CHG-GHOST"),
            fixture.t0.plusSeconds(1_800),
            fixture.t0.plusSeconds(2_196),
            49_250L,
            52_000L,
            275_000L);
    return RuleProbeSupport.scheduleProbe(fixture, List.of(session));
  }
}
