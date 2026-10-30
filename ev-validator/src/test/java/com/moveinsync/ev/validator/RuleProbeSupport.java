package com.moveinsync.ev.validator;

import com.moveinsync.ev.domain.consumption.ConstantConsumptionModel;
import com.moveinsync.ev.domain.distance.ConstantSpeedDistanceProvider;
import com.moveinsync.ev.domain.distance.DistanceDuration;
import com.moveinsync.ev.domain.distance.DistanceProvider;
import com.moveinsync.ev.domain.distance.MatrixDistanceProvider;
import com.moveinsync.ev.domain.hub.Charger;
import com.moveinsync.ev.domain.schedule.ChargingSession;
import com.moveinsync.ev.domain.schedule.FleetPlan;
import com.moveinsync.ev.domain.schedule.RouteActivity;
import com.moveinsync.ev.domain.schedule.TripAssignment;
import com.moveinsync.ev.domain.schedule.UnassignedTrip;
import com.moveinsync.ev.domain.schedule.VehicleSchedule;
import com.moveinsync.ev.domain.tariff.TariffWindow;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

/** Shared builders for the seeded rule catalog. */
final class RuleProbeSupport {
  static RuleProbe scheduleProbe(PlanValidatorFuzzFixture fixture, List<RouteActivity> activities) {
    VehicleSchedule schedule =
        new VehicleSchedule(fixture.cab, fixture.pickup, fixture.startSoc, fixture.t0, activities);
    return new RuleProbe(fixture.input, plan(fixture, List.of(schedule), List.of()));
  }

  static TripAssignment assignment(
      PlanValidatorFuzzFixture fixture,
      java.time.Instant pickup,
      java.time.Instant dropoff,
      long arrival,
      long departure) {
    return new TripAssignment(
        fixture.trip, fixture.cab.id(), pickup, dropoff, 0, 0, 0L, arrival, departure);
  }

  static ChargingSession copySession(
      PlanValidatorFuzzFixture fixture, String id, long startSoc, long targetSoc) {
    ChargingSession session = fixture.session;
    return new ChargingSession(
        id,
        session.vehicleId(),
        session.hubId(),
        session.chargerId(),
        session.startTime(),
        session.endTime(),
        startSoc,
        targetSoc,
        session.costMicros());
  }

  static FleetPlan plan(
      PlanValidatorFuzzFixture fixture,
      List<VehicleSchedule> schedules,
      List<UnassignedTrip> unassigned) {
    return new FleetPlan(
        fixture.validPlan.planId(),
        fixture.tenant.value(),
        fixture.t0,
        fixture.t0.plusSeconds(86_400),
        schedules,
        unassigned,
        1L,
        "h");
  }

  static ValidationInput input(
      PlanValidatorFuzzFixture fixture,
      boolean reserve,
      Charger charger,
      DistanceProvider distanceProvider,
      long initialSoc) {
    return ValidationInput.builder()
        .tenantId(fixture.tenant)
        .horizon(fixture.t0, fixture.t0.plusSeconds(86_400))
        .addHub(fixture.hub)
        .addCharger(charger)
        .addVehicle(fixture.cab, fixture.pickup, initialSoc)
        .addTrip(fixture.trip)
        .addTariff(tariff(fixture))
        .distanceProvider(distanceProvider)
        .consumptionModel(new ConstantConsumptionModel())
        .batteryFloorPercentage(15)
        .enforceReserveToHub(reserve)
        .build();
  }

  static TariffWindow tariff(PlanValidatorFuzzFixture fixture) {
    return new TariffWindow(fixture.hub.id(), LocalTime.of(0, 0), LocalTime.of(23, 59), 100_000L);
  }

  static DistanceProvider distances(PlanValidatorFuzzFixture fixture) {
    Map<MatrixDistanceProvider.LocationPair, DistanceDuration> matrix =
        Map.of(
            new MatrixDistanceProvider.LocationPair(fixture.pickup.id(), fixture.dropoff.id()),
            DistanceDuration.of(5_000, 600),
            new MatrixDistanceProvider.LocationPair(fixture.dropoff.id(), fixture.depot.id()),
            DistanceDuration.of(0, 0));
    return new MatrixDistanceProvider(matrix, new ConstantSpeedDistanceProvider(5_000, 10));
  }
}
