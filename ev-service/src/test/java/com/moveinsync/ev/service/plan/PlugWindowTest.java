package com.moveinsync.ev.service.plan;

import static org.assertj.core.api.Assertions.assertThat;

import com.moveinsync.ev.domain.hub.Charger;
import com.moveinsync.ev.domain.hub.ChargerId;
import com.moveinsync.ev.domain.hub.ChargerStatus;
import com.moveinsync.ev.domain.hub.Hub;
import com.moveinsync.ev.domain.hub.HubId;
import com.moveinsync.ev.domain.hub.OutageWindow;
import com.moveinsync.ev.domain.location.Location;
import com.moveinsync.ev.domain.schedule.ChargingSession;
import com.moveinsync.ev.domain.schedule.FleetPlan;
import com.moveinsync.ev.domain.schedule.RouteActivity;
import com.moveinsync.ev.domain.schedule.VehicleSchedule;
import com.moveinsync.ev.domain.tenant.TenantId;
import com.moveinsync.ev.domain.trip.Trip;
import com.moveinsync.ev.domain.trip.TripId;
import com.moveinsync.ev.domain.vehicle.Vehicle;
import com.moveinsync.ev.domain.vehicle.VehicleId;
import com.moveinsync.ev.planner.config.SolverConfig;
import com.moveinsync.ev.planner.construct.GreedyConstructionSolver;
import com.moveinsync.ev.planner.input.CabShift;
import com.moveinsync.ev.planner.input.PlannerInput;
import com.moveinsync.ev.service.fleet.FleetDefaults;
import com.moveinsync.ev.validator.PlanValidator;
import com.moveinsync.ev.validator.RuleId;
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
 * Plug A is down on {@code [0, 10)} and plug B on {@code [10, 20)}. A session covering both
 * intervals fits neither plug.
 */
class PlugWindowTest {

  private static final TenantId TENANT = TenantId.of("tenant-plug");
  private static final Instant START = DriftFleet.START;
  private static final Instant TEN = START.plusSeconds(10);
  private static final Instant TWENTY = START.plusSeconds(20);
  private static final Location DEPOT = Location.of("LOC-HUB", "Hub");

  @Test
  void thePlannerDoesNotEmitASessionAcrossBothOutages() {
    PlannerInput fleet = fleet();
    Clock clock = Clock.fixed(START, ZoneOffset.UTC);
    SolverConfig config = SolverConfig.safeBaseline(START.plusSeconds(30), 11L);
    FleetPlan plan = new GreedyConstructionSolver().solve(fleet, config, clock).plan();

    for (VehicleSchedule schedule : plan.vehicleSchedules()) {
      for (RouteActivity activity : schedule.activities()) {
        if (activity instanceof ChargingSession session) {
          boolean spans = session.startTime().equals(START) && session.endTime().equals(TWENTY);
          assertThat(spans).isFalse();
          assertThat(
                  plug(fleet, session.chargerId().value())
                      .isAvailableDuring(session.startTime(), session.endTime()))
              .isTrue();
        }
      }
    }
  }

  @Test
  void theValidatorRejectsASessionAcrossBothOutages() {
    PlannerInput fleet = fleet();
    ChargingSession session =
        new ChargingSession(
            "SESS-SPAN",
            VehicleId.of("CAB-1"),
            HubId.of("HUB-1"),
            ChargerId.of("CHG-A"),
            START,
            TWENTY,
            12_000L,
            20_000L,
            0L);
    FleetPlan plan =
        new FleetPlan(
            "plan-span",
            TENANT.value(),
            fleet.horizonStart(),
            fleet.horizonEnd(),
            List.of(schedule(session)),
            List.of(),
            11L,
            fleet.inputHash());

    ValidationReport report = new PlanValidator().validate(ground(fleet), plan);

    assertThat(report.violationsFor(RuleId.CHARGER_UNAVAILABLE)).isNotEmpty();
  }

  private static PlannerInput fleet() {
    Hub hub = Hub.of(TENANT.value(), "HUB-1", "Hub", DEPOT, ZoneId.of("UTC"), 2);
    Charger first =
        new Charger(
            TENANT,
            ChargerId.of("CHG-A"),
            hub.id(),
            50,
            ChargerStatus.OPERATIONAL,
            List.of(new OutageWindow(START, TEN, "plug-a")));
    Charger second =
        new Charger(
            TENANT,
            ChargerId.of("CHG-B"),
            hub.id(),
            50,
            ChargerStatus.OPERATIONAL,
            List.of(new OutageWindow(TEN, TWENTY, "plug-b")));
    return PlannerInput.builder()
        .tenantId(TENANT)
        .horizon(START, DriftFleet.END)
        .addHub(hub)
        .addCharger(first)
        .addCharger(second)
        .addCab(cab())
        .addTrip(trip())
        .distanceProvider(FleetDefaults.distance())
        .consumptionModel(FleetDefaults.consumption())
        .chargingCurve(FleetDefaults.curve())
        .build();
  }

  private static Charger plug(PlannerInput fleet, String id) {
    for (Charger charger : fleet.chargers()) {
      if (charger.id().value().equals(id)) {
        return charger;
      }
    }
    throw new IllegalStateException("charger is not in the fleet");
  }

  private static VehicleSchedule schedule(ChargingSession session) {
    CabShift cab = cab();
    return new VehicleSchedule(
        cab.vehicle(),
        cab.startLocation(),
        cab.initialSocWh(),
        cab.availableFrom(),
        List.of(session));
  }

  private static CabShift cab() {
    return new CabShift(
        new Vehicle(TENANT, VehicleId.of("CAB-1"), 60_000L, 150L, 50), DEPOT, 12_000L, START);
  }

  private static Trip trip() {
    Instant pickup = START.plusSeconds(3_600);
    return new Trip(
        TENANT,
        TripId.of("TRIP-1"),
        pickup,
        Location.of("LOC-P", "Pickup"),
        Location.of("LOC-D", "Drop"),
        10_000,
        1_200,
        pickup.plusSeconds(1_200),
        "EMP-1");
  }

  private static ValidationInput ground(PlannerInput fleet) {
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
    fleet.hubs().forEach(builder::addHub);
    fleet.chargers().forEach(builder::addCharger);
    return builder.build();
  }
}
