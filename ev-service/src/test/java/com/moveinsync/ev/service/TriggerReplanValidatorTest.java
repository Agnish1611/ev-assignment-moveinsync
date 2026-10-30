package com.moveinsync.ev.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.moveinsync.ev.domain.consumption.ConstantConsumptionModel;
import com.moveinsync.ev.domain.distance.ConstantSpeedDistanceProvider;
import com.moveinsync.ev.domain.hub.Charger;
import com.moveinsync.ev.domain.hub.ChargerStatus;
import com.moveinsync.ev.domain.hub.Hub;
import com.moveinsync.ev.domain.location.Location;
import com.moveinsync.ev.domain.schedule.ChargingSession;
import com.moveinsync.ev.domain.schedule.FleetPlan;
import com.moveinsync.ev.domain.schedule.RouteActivity;
import com.moveinsync.ev.domain.schedule.TripAssignment;
import com.moveinsync.ev.domain.schedule.VehicleSchedule;
import com.moveinsync.ev.domain.tenant.TenantId;
import com.moveinsync.ev.domain.trip.Trip;
import com.moveinsync.ev.domain.trip.TripId;
import com.moveinsync.ev.domain.vehicle.Vehicle;
import com.moveinsync.ev.domain.vehicle.VehicleId;
import com.moveinsync.ev.planner.config.SolverConfig;
import com.moveinsync.ev.planner.construct.GreedyConstructionSolver;
import com.moveinsync.ev.planner.construct.LadderReplanSolver;
import com.moveinsync.ev.planner.input.CabShift;
import com.moveinsync.ev.planner.input.PlannerInput;
import com.moveinsync.ev.planner.solve.DriftEvent;
import com.moveinsync.ev.validator.PlanValidator;
import com.moveinsync.ev.validator.ValidationInput;
import com.moveinsync.ev.validator.ValidationInputBuilder;
import com.moveinsync.ev.validator.ValidationReport;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

/**
 * Charger failure and a cab below the floor are repaired in the planner and checked here. The
 * planner module cannot depend on the validator.
 */
class TriggerReplanValidatorTest {

  private static final TenantId TENANT = TenantId.of("tenant-a");
  private static final Instant START = Instant.parse("2026-10-30T08:00:00Z");
  private static final Instant END = START.plusSeconds(28_800);
  private static final Location DEPOT = Location.of("LOC-HUB", "Hub");

  private final LadderReplanSolver solver = new LadderReplanSolver();
  private final SolverConfig config = SolverConfig.safeBaseline(END, 11L);
  private final Clock clock = Clock.fixed(START, ZoneOffset.UTC);

  @Test
  void aCabBelowTheFloorIsOmittedAndThePlanStaysValid() {
    Trip trip = trip("TRIP-1", 3_600);
    PlannerInput fleet =
        shell(1).addCab(cab("CAB-A", 8_000L)).addCab(cab("CAB-B", 50_000L)).addTrip(trip).build();
    FleetPlan current =
        new FleetPlan(
            "plan-floor",
            fleet.tenantId(),
            fleet.horizonStart(),
            fleet.horizonEnd(),
            List.of(schedule(fleet, "CAB-A", trip, 8_000L)),
            List.of(),
            11L,
            fleet.inputHash());

    FleetPlan repaired =
        solver
            .replan(
                fleet,
                current,
                new DriftEvent(DriftEvent.Kind.BATTERY, "CAB-A", START),
                config,
                clock)
            .plan();

    assertThat(repaired.findVehicleSchedule(VehicleId.of("CAB-A"))).isEmpty();
    assertThat(cabOf(repaired, "TRIP-1")).isEqualTo("CAB-B");
    ValidationReport report = new PlanValidator().validate(groundTruth(fleet, ""), repaired);
    assertThat(report.isValid()).as(report.toDetailedSummary()).isTrue();
  }

  @Test
  void aFailedPlugLeavesAValidPlanOnTheOtherPlug() {
    PlannerInput fleet =
        shell(2)
            .addCharger(Charger.of(TENANT.value(), "CHG-2", "HUB-1", 50))
            .addCab(cab("CAB-1", 12_000L))
            .addTrip(trip("TRIP-C", 3_600))
            .build();
    FleetPlan current = new GreedyConstructionSolver().solve(fleet, config, clock).plan();
    ChargingSession session = onlySession(current);
    DriftEvent event =
        new DriftEvent(
            DriftEvent.Kind.CHARGER_FAILURE,
            session.chargerId().value(),
            session.startTime(),
            OptionalLong.of(session.startSocWh()));

    FleetPlan repaired = solver.replan(fleet, current, event, config, clock).plan();

    assertThat(cabOf(repaired, "TRIP-C")).isEqualTo("CAB-1");
    assertThat(onlySession(repaired).chargerId()).isNotEqualTo(session.chargerId());
    ValidationReport report =
        new PlanValidator().validate(groundTruth(fleet, session.chargerId().value()), repaired);
    assertThat(report.isValid()).as(report.toDetailedSummary()).isTrue();
  }

  private static String cabOf(FleetPlan plan, String tripId) {
    for (VehicleSchedule schedule : plan.vehicleSchedules()) {
      for (RouteActivity activity : schedule.activities()) {
        if (activity instanceof TripAssignment assignment
            && assignment.trip().id().value().equals(tripId)) {
          return schedule.vehicleId().value();
        }
      }
    }
    return "";
  }

  private static ChargingSession onlySession(FleetPlan plan) {
    for (VehicleSchedule schedule : plan.vehicleSchedules()) {
      for (RouteActivity activity : schedule.activities()) {
        if (activity instanceof ChargingSession session) {
          return session;
        }
      }
    }
    throw new AssertionError("missing charging session");
  }

  private static VehicleSchedule schedule(PlannerInput input, String cabId, Trip trip, long socWh) {
    for (CabShift cab : input.cabs()) {
      if (cab.vehicle().id().value().equals(cabId)) {
        return new VehicleSchedule(
            cab.vehicle(),
            cab.startLocation(),
            socWh,
            cab.availableFrom(),
            List.of(
                new TripAssignment(
                    trip,
                    cab.vehicle().id(),
                    trip.startTs(),
                    trip.plannedDropoffTs(),
                    0,
                    0,
                    0L,
                    1L,
                    1L)));
      }
    }
    throw new IllegalStateException("cab is not in the fleet");
  }

  private static PlannerInput.Builder shell(int chargerCount) {
    Hub hub = Hub.of(TENANT.value(), "HUB-1", "Hub", DEPOT, ZoneId.of("UTC"), chargerCount);
    return PlannerInput.builder()
        .tenantId(TENANT)
        .horizon(START, END)
        .addHub(hub)
        .addCharger(Charger.of(TENANT.value(), "CHG-1", "HUB-1", 50))
        .distanceProvider(new ConstantSpeedDistanceProvider(5_000, 10))
        .consumptionModel(new ConstantConsumptionModel());
  }

  private static CabShift cab(String id, long socWh) {
    return new CabShift(
        new Vehicle(TENANT, VehicleId.of(id), 60_000L, 150L, 50), DEPOT, socWh, START);
  }

  private static Trip trip(String id, long offsetSeconds) {
    Instant pickup = START.plusSeconds(offsetSeconds);
    return new Trip(
        TENANT,
        TripId.of(id),
        pickup,
        Location.of("LOC-P", "Pickup"),
        Location.of("LOC-D", "Drop"),
        10_000,
        1_200,
        pickup.plusSeconds(3_600),
        "EMP-1");
  }

  private static ValidationInput groundTruth(PlannerInput fleet, String faultyChargerId) {
    ValidationInputBuilder builder =
        new ValidationInputBuilder()
            .tenantId(fleet.tenantId())
            .horizon(fleet.horizonStart(), fleet.horizonEnd())
            .distanceProvider(fleet.distanceProvider())
            .consumptionModel(fleet.consumptionModel())
            .chargingCurve(fleet.chargingCurve())
            .batteryFloorPercentage(15)
            .enforceReserveToHub(true)
            .idlePowerW(0);
    fleet
        .cabs()
        .forEach(cab -> builder.addVehicle(cab.vehicle(), cab.startLocation(), cab.initialSocWh()));
    fleet.trips().forEach(builder::addTrip);
    fleet.hubs().forEach(builder::addHub);
    for (Charger charger : fleet.chargers()) {
      builder.addCharger(charger.id().value().equals(faultyChargerId) ? faulty(charger) : charger);
    }
    fleet.tariffs().forEach(schedule -> schedule.windows().forEach(builder::addUtcTariff));
    return builder.build();
  }

  private static Charger faulty(Charger charger) {
    return new Charger(
        charger.tenantId(),
        charger.id(),
        charger.hubId(),
        charger.powerKw(),
        ChargerStatus.FAULTY,
        charger.outageWindows());
  }
}
