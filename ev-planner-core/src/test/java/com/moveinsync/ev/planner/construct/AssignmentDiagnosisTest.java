package com.moveinsync.ev.planner.construct;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.moveinsync.ev.domain.consumption.ConstantConsumptionModel;
import com.moveinsync.ev.domain.distance.ConstantSpeedDistanceProvider;
import com.moveinsync.ev.domain.hub.Charger;
import com.moveinsync.ev.domain.hub.ChargerId;
import com.moveinsync.ev.domain.hub.ChargerStatus;
import com.moveinsync.ev.domain.hub.Hub;
import com.moveinsync.ev.domain.location.Location;
import com.moveinsync.ev.domain.schedule.FleetPlan;
import com.moveinsync.ev.domain.schedule.UnassignedReasonCode;
import com.moveinsync.ev.domain.schedule.UnassignedTrip;
import com.moveinsync.ev.domain.schedule.VehicleSchedule;
import com.moveinsync.ev.domain.trip.Trip;
import com.moveinsync.ev.planner.PlannerFixtures;
import com.moveinsync.ev.planner.config.SolverConfig;
import com.moveinsync.ev.planner.input.CabShift;
import com.moveinsync.ev.planner.input.PlannerInput;
import java.time.Clock;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class AssignmentDiagnosisTest {

  private final SolverConfig config = SolverConfig.safeBaseline(PlannerFixtures.END, 7L);
  private final Clock early = Clock.fixed(PlannerFixtures.START, ZoneOffset.UTC);

  @Test
  void probesNameTheBindingConstraint() {
    assertThat(code(timeFleet())).isEqualTo(UnassignedReasonCode.NO_CAB_FREE_IN_TIME_WINDOW);
    assertThat(code(energyFleet())).isEqualTo(UnassignedReasonCode.NO_CAB_WITH_ENOUGH_CHARGE);
    assertThat(code(reserveFleet())).isEqualTo(UnassignedReasonCode.RESERVE_TO_HUB_VIOLATION);
    TripDiagnosis intrinsic = only(intrinsicFleet());
    assertThat(intrinsic.reasonCode()).isEqualTo(UnassignedReasonCode.INTRINSICALLY_INFEASIBLE);
    assertThat(intrinsic.findings()).isEmpty();
    assertThat(intrinsic.explanation()).doesNotContain("@");
  }

  @Test
  void anEnergyMissReportsTheArrivalSoc() {
    TripDiagnosis diagnosis = only(energyFleet());
    CabFinding finding = diagnosis.findings().get(0);

    assertThat(finding.constraint()).isEqualTo(BindingConstraint.ENERGY);
    assertThat(finding.arrivalSocWh()).hasValue(10_900L);
    assertThat(finding.floorWh()).isEqualTo(10_800L);
  }

  @Test
  void oneExtraPlugNamesTheHubAndTheSessionWindow() {
    PlannerInput fleet = crowdedFleet();
    FleetPlan plan = new GreedyConstructionSolver().solve(fleet, config, early).plan();
    TripDiagnosis diagnosis = AssignmentDiagnosis.explain(fleet, config, plan).get(0);

    assertThat(diagnosis.reasonCode()).isEqualTo(UnassignedReasonCode.CHARGER_CONGESTION);
    assertThat(diagnosis.hubId()).contains("HUB-1");
    assertThat(diagnosis.windowStart()).isPresent();
    assertThat(diagnosis.windowEnd()).isPresent();
    assertThat(diagnosis.windowStart().orElseThrow()).isBefore(diagnosis.windowEnd().orElseThrow());
    assertThat(diagnosis.explanation())
        .contains("HUB-1")
        .doesNotContain("CHG-RELAX")
        .doesNotContain("@");
    assertThat(diagnosis.findings())
        .anyMatch(finding -> finding.constraint() == BindingConstraint.CONGESTED);
    assertThat(AssignmentDiagnosis.explain(fleet, config, plan)).isEqualTo(List.of(diagnosis));
  }

  @Test
  void aTripLeftUnassignedThatStillFitsSaysACabCanTakeIt() {
    PlannerInput fleet =
        PlannerFixtures.shell()
            .addCab(PlannerFixtures.cab("CAB-1", 50_000L))
            .addTrip(PlannerFixtures.trip("TRIP-OK", PlannerFixtures.START.plusSeconds(3_600)))
            .build();

    TripDiagnosis diagnosis = only(fleet);

    assertThat(diagnosis.reasonCode()).isEqualTo(UnassignedReasonCode.OTHER);
    assertThat(diagnosis.explanation()).isEqualTo("a cab can take this trip");
    assertThat(diagnosis.findings().get(0).constraint()).isEqualTo(BindingConstraint.FEASIBLE);
  }

  @Test
  void anExtraPlugIsRefusedWhenTheHubCannotGrow() {
    Hub hub =
        Hub.of(
            PlannerFixtures.TENANT.value(),
            "HUB-1",
            "Hub",
            PlannerFixtures.depot(),
            ZoneId.of("UTC"),
            1);
    PlannerInput faulty =
        PlannerInput.builder()
            .tenantId(PlannerFixtures.TENANT)
            .horizon(PlannerFixtures.START, PlannerFixtures.END)
            .addHub(hub)
            .addCharger(
                new Charger(
                    PlannerFixtures.TENANT,
                    ChargerId.of("CHG-DOWN"),
                    hub.id(),
                    50,
                    ChargerStatus.FAULTY,
                    List.of()))
            .distanceProvider(new ConstantSpeedDistanceProvider(5_000, 10))
            .consumptionModel(new ConstantConsumptionModel())
            .build();
    Hub full =
        new Hub(
            hub.tenantId(),
            hub.id(),
            hub.name(),
            hub.location(),
            hub.timeZone(),
            Integer.MAX_VALUE);
    PlannerInput saturated =
        PlannerInput.builder()
            .tenantId(faulty.tenantId())
            .horizon(faulty.horizonStart(), faulty.horizonEnd())
            .addHub(full)
            .addCharger(Charger.of(PlannerFixtures.TENANT.value(), "CHG-1", "HUB-1", 50))
            .distanceProvider(faulty.distanceProvider())
            .consumptionModel(faulty.consumptionModel())
            .build();
    PlannerInput named =
        PlannerFixtures.shell()
            .addCharger(Charger.of(PlannerFixtures.TENANT.value(), "CHG-RELAX-HUB-1", "HUB-1", 50))
            .build();

    assertThat(RelaxedFleet.oneMorePlug(faulty, hub)).isEmpty();
    assertThat(RelaxedFleet.oneMorePlug(saturated, full)).isEmpty();
    assertThat(RelaxedFleet.oneMorePlug(named, named.hubs().get(0))).isEmpty();
    assertThat(
            RelaxedFleet.oneMorePlug(
                named, Hub.of("tenant-a", "HUB-X", "Hub", hub.location(), ZoneId.of("UTC"), 1)))
        .isEmpty();
  }

  @Test
  void nullInputsAndADuplicateScheduleAreRejected() {
    PlannerInput fleet = timeFleet();
    FleetPlan plan = parked(fleet);
    assertThatThrownBy(() -> AssignmentDiagnosis.explain(null, config, plan))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> AssignmentDiagnosis.explain(fleet, null, plan))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> AssignmentDiagnosis.explain(fleet, config, null))
        .isInstanceOf(NullPointerException.class);
    VehicleSchedule schedule = plan.vehicleSchedules().get(0);
    FleetPlan duplicated =
        new FleetPlan(
            "plan-1",
            fleet.tenantId(),
            fleet.horizonStart(),
            fleet.horizonEnd(),
            List.of(schedule, schedule),
            plan.unassignedTrips(),
            1L,
            fleet.inputHash());
    assertThatThrownBy(() -> AssignmentDiagnosis.explain(fleet, config, duplicated))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private UnassignedReasonCode code(PlannerInput fleet) {
    return only(fleet).reasonCode();
  }

  private TripDiagnosis only(PlannerInput fleet) {
    List<TripDiagnosis> diagnoses = AssignmentDiagnosis.explain(fleet, config, parked(fleet));
    assertThat(diagnoses).hasSize(1);
    return diagnoses.get(0);
  }

  private static FleetPlan parked(PlannerInput fleet) {
    List<VehicleSchedule> schedules = new java.util.ArrayList<>();
    for (CabShift cab : fleet.cabs()) {
      schedules.add(
          new VehicleSchedule(
              cab.vehicle(),
              cab.startLocation(),
              cab.initialSocWh(),
              cab.availableFrom(),
              List.of()));
    }
    List<UnassignedTrip> unassigned = new java.util.ArrayList<>();
    for (Trip trip : fleet.trips()) {
      unassigned.add(UnassignedTrip.of(trip, UnassignedReasonCode.OTHER, "parked"));
    }
    return new FleetPlan(
        "plan-1",
        fleet.tenantId(),
        fleet.horizonStart(),
        fleet.horizonEnd(),
        schedules,
        unassigned,
        1L,
        fleet.inputHash());
  }

  private static PlannerInput timeFleet() {
    return PlannerFixtures.shell()
        .addCab(PlannerFixtures.cab("CAB-1", 50_000L))
        .addTrip(PlannerFixtures.trip("TRIP-T", PlannerFixtures.START.plusSeconds(10)))
        .build();
  }

  private static PlannerInput energyFleet() {
    Location pickup = Location.of("LOC-P", "Pickup");
    return PlannerFixtures.shell()
        .addCab(PlannerFixtures.cab("CAB-1", 10_900L, 150L, pickup))
        .addTrip(
            PlannerFixtures.trip(
                "TRIP-E",
                PlannerFixtures.START.plusSeconds(600),
                pickup,
                Location.of("LOC-D", "Drop"),
                10_000))
        .build();
  }

  private static PlannerInput reserveFleet() {
    Location pickup = Location.of("LOC-P", "Pickup");
    return PlannerFixtures.shell()
        .addCab(PlannerFixtures.cab("CAB-1", 12_500L, 150L, pickup))
        .addTrip(
            PlannerFixtures.trip(
                "TRIP-H",
                PlannerFixtures.START.plusSeconds(600),
                pickup,
                Location.of("LOC-D", "Drop"),
                10_000))
        .build();
  }

  private static PlannerInput intrinsicFleet() {
    return PlannerFixtures.shell()
        .addCab(PlannerFixtures.cab("CAB-1", 50_000L))
        .addTrip(
            PlannerFixtures.trip(
                "TRIP-I",
                PlannerFixtures.START.plusSeconds(600),
                Location.of("LOC-P", "Pickup"),
                Location.of("LOC-D", "Drop"),
                1_000_000))
        .build();
  }

  private static PlannerInput crowdedFleet() {
    Hub hub =
        Hub.of(
            PlannerFixtures.TENANT.value(),
            "HUB-1",
            "Hub",
            PlannerFixtures.depot(),
            ZoneId.of("UTC"),
            1);
    return PlannerInput.builder()
        .tenantId(PlannerFixtures.TENANT)
        .horizon(PlannerFixtures.START, PlannerFixtures.END)
        .addHub(hub)
        .addCharger(Charger.of(PlannerFixtures.TENANT.value(), "CHG-1", "HUB-1", 50))
        .addCab(PlannerFixtures.cab("CAB-1", 12_000L))
        .addCab(PlannerFixtures.cab("CAB-2", 12_000L))
        .addTrip(
            PlannerFixtures.trip(
                "TRIP-A",
                PlannerFixtures.START.plusSeconds(700),
                Location.of("LOC-P", "Pickup"),
                Location.of("LOC-D", "Drop"),
                10_000))
        .addTrip(
            PlannerFixtures.trip(
                "TRIP-B",
                PlannerFixtures.START.plusSeconds(700),
                Location.of("LOC-P", "Pickup"),
                Location.of("LOC-D", "Drop"),
                10_000))
        .distanceProvider(new ConstantSpeedDistanceProvider(5_000, 10))
        .consumptionModel(new ConstantConsumptionModel())
        .build();
  }
}
