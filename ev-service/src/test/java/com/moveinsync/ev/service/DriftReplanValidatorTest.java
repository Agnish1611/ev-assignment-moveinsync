package com.moveinsync.ev.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.moveinsync.ev.domain.consumption.ConstantConsumptionModel;
import com.moveinsync.ev.domain.distance.ConstantSpeedDistanceProvider;
import com.moveinsync.ev.domain.hub.Charger;
import com.moveinsync.ev.domain.hub.Hub;
import com.moveinsync.ev.domain.location.Location;
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
import com.moveinsync.ev.planner.construct.LadderReplanSolver;
import com.moveinsync.ev.planner.input.CabShift;
import com.moveinsync.ev.planner.input.PlannerInput;
import com.moveinsync.ev.planner.solve.DriftEvent;
import com.moveinsync.ev.planner.solve.SolveResult;
import com.moveinsync.ev.validator.PlanValidator;
import com.moveinsync.ev.validator.ValidationInput;
import com.moveinsync.ev.validator.ValidationInputBuilder;
import com.moveinsync.ev.validator.ValidationReport;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The 22 percent reading is repaired in the planner and checked by the independent validator at 15
 * percent. The planner module cannot depend on the validator, so the check lives here.
 */
class DriftReplanValidatorTest {

  private static final TenantId TENANT = TenantId.of("tenant-a");
  private static final Instant START = Instant.parse("2026-10-30T08:00:00Z");
  private static final Instant END = START.plusSeconds(28_800);
  private static final Location DEPOT = Location.of("LOC-HUB", "Hub");

  @Test
  void aTwentyTwoPercentReadingPassesTheValidator() {
    Trip next = trip("TRIP-NEXT", 500, 10_000, 1_200);
    Trip locked = trip("TRIP-LOCKED", 500, 10_000, 1_200);
    Trip later = trip("TRIP-LATER", 7_200, 10_000, 1_200);
    PlannerInput drifted =
        shell()
            .addCab(cab("CAB-A", 13_200L))
            .addCab(cab("CAB-B", 50_000L))
            .addCab(cab("CAB-C", 50_000L))
            .addTrip(next)
            .addTrip(locked)
            .addTrip(later)
            .build();
    FleetPlan claimedAtFortyOne =
        new FleetPlan(
            "plan-41",
            drifted.tenantId(),
            drifted.horizonStart(),
            drifted.horizonEnd(),
            List.of(
                schedule(drifted, "CAB-A", next, 24_600L),
                schedule(drifted, "CAB-B", locked, 50_000L),
                schedule(drifted, "CAB-C", later, 50_000L)),
            List.of(),
            11L,
            drifted.inputHash());
    SolverConfig config = SolverConfig.safeBaseline(START.plusSeconds(2), 11L);

    SolveResult result =
        new LadderReplanSolver()
            .replan(
                drifted,
                claimedAtFortyOne,
                new DriftEvent(DriftEvent.Kind.BATTERY, "CAB-A", START),
                config,
                Clock.fixed(START, ZoneOffset.UTC));

    assertThat(cabOf(result.plan(), "TRIP-NEXT")).isEqualTo("CAB-C");
    assertThat(cabOf(result.plan(), "TRIP-LOCKED")).isEqualTo("CAB-B");
    assertThat(result.score().changedAssignments()).isLessThanOrEqualTo(3);
    assertThat(result.deadlineExhausted()).isFalse();
    ValidationReport report = new PlanValidator().validate(groundTruth(drifted), result.plan());
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

  private static PlannerInput.Builder shell() {
    Hub hub = Hub.of(TENANT.value(), "HUB-1", "Hub", DEPOT, ZoneId.of("UTC"), 1);
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

  private static Trip trip(String id, long offsetSeconds, int meters, int durationSeconds) {
    Instant pickup = START.plusSeconds(offsetSeconds);
    return new Trip(
        TENANT,
        TripId.of(id),
        pickup,
        Location.of("LOC-P", "Pickup"),
        Location.of("LOC-D", "Drop"),
        meters,
        durationSeconds,
        pickup.plusSeconds(durationSeconds),
        "EMP-1");
  }

  private static ValidationInput groundTruth(PlannerInput fleet) {
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
    fleet.chargers().forEach(builder::addCharger);
    fleet.tariffs().forEach(schedule -> schedule.windows().forEach(builder::addUtcTariff));
    return builder.build();
  }
}
