package com.moveinsync.ev.validator;

import com.moveinsync.ev.domain.consumption.ConstantConsumptionModel;
import com.moveinsync.ev.domain.distance.ConstantSpeedDistanceProvider;
import com.moveinsync.ev.domain.distance.DistanceDuration;
import com.moveinsync.ev.domain.distance.DistanceProvider;
import com.moveinsync.ev.domain.distance.MatrixDistanceProvider;
import com.moveinsync.ev.domain.hub.Charger;
import com.moveinsync.ev.domain.hub.Hub;
import com.moveinsync.ev.domain.location.Location;
import com.moveinsync.ev.domain.schedule.ChargingSession;
import com.moveinsync.ev.domain.schedule.FleetPlan;
import com.moveinsync.ev.domain.schedule.TripAssignment;
import com.moveinsync.ev.domain.schedule.VehicleSchedule;
import com.moveinsync.ev.domain.tariff.TariffWindow;
import com.moveinsync.ev.domain.tenant.TenantId;
import com.moveinsync.ev.domain.trip.Trip;
import com.moveinsync.ev.domain.trip.TripId;
import com.moveinsync.ev.domain.vehicle.Vehicle;
import com.moveinsync.ev.domain.vehicle.VehicleId;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Random;

/** Shared fixture generator for PlanValidator mutation and property fuzz tests. */
final class PlanValidatorFuzzFixture {

  final TenantId tenant = TenantId.of("tenant-fuzz");
  final Location depot = Location.of("LOC-DEPOT", "Depot");
  final Location pickup = Location.of("LOC-PICK", "Pickup");
  final Location dropoff = Location.of("LOC-DROP", "Dropoff");
  final Instant t0 = Instant.parse("2026-10-30T08:00:00Z");

  final Hub hub;
  final Charger charger;
  final Vehicle cab;
  final Trip trip;
  final TripAssignment assignment;
  final ChargingSession session;
  final VehicleSchedule schedule;
  final FleetPlan validPlan;
  final ValidationInput input;
  final long startSoc = 50_000L;

  PlanValidatorFuzzFixture(int trial, Random rng) {
    hub = Hub.of("tenant-fuzz", "HUB-1", "H1", depot, ZoneId.of("UTC"), 2);
    charger = Charger.of("tenant-fuzz", "CHG-1", "HUB-1", 50);
    cab = new Vehicle(tenant, VehicleId.of("CAB-01"), 60_000L, 150L, 50);

    trip =
        new Trip(
            tenant,
            TripId.of("TRIP-FZ-" + trial),
            t0.plusSeconds(600),
            pickup,
            dropoff,
            5000,
            600,
            t0.plusSeconds(3600),
            "EMP");

    // 5km trip @ 150 Wh/km = 750 Wh. Dropoff SoC = 50,000 - 750 = 49,250 Wh
    assignment =
        new TripAssignment(
            trip, cab.id(), t0.plusSeconds(600), t0.plusSeconds(1200), 0, 0, 0L, 50_000L, 49_250L);

    // Charge from 49,250 to 52,000 Wh (2,750 Wh above 80% threshold of 48,000 Wh)
    // In slow segment (50% of 50 kW = 25 kW): 2,750 * 3600 / 25,000 = 396 s
    long cost = 275_000L; // 2.75 kWh @ 100,000 micros/kWh
    long duration = 396L;
    session =
        new ChargingSession(
            "S-FZ-" + trial,
            cab.id(),
            hub.id(),
            charger.id(),
            t0.plusSeconds(1800),
            t0.plusSeconds(1800 + duration),
            49_250L,
            52_000L,
            cost);

    schedule = new VehicleSchedule(cab, pickup, startSoc, t0, List.of(assignment, session));
    validPlan =
        new FleetPlan(
            "P-FZ-" + trial,
            tenant.value(),
            t0,
            t0.plusSeconds(86400),
            List.of(schedule),
            List.of(),
            1L,
            "h");

    Map<MatrixDistanceProvider.LocationPair, DistanceDuration> matrix =
        Map.of(
            new MatrixDistanceProvider.LocationPair(pickup.id(), dropoff.id()),
            DistanceDuration.of(5000, 600),
            new MatrixDistanceProvider.LocationPair(dropoff.id(), depot.id()),
            DistanceDuration.of(0, 0));
    DistanceProvider dist =
        new MatrixDistanceProvider(matrix, new ConstantSpeedDistanceProvider(5000, 10));

    input =
        ValidationInput.builder()
            .tenantId(tenant)
            .horizon(t0, t0.plusSeconds(86400))
            .addHub(hub)
            .addCharger(charger)
            .addVehicle(cab, pickup, startSoc)
            .addTrip(trip)
            .addTariff(
                new TariffWindow(hub.id(), LocalTime.of(0, 0), LocalTime.of(23, 59), 100_000L))
            .distanceProvider(dist)
            .consumptionModel(new ConstantConsumptionModel())
            .batteryFloorPercentage(15)
            .enforceReserveToHub(false)
            .build();
  }

  FleetPlan planWithSchedule(VehicleSchedule sched) {
    return new FleetPlan(
        validPlan.planId(),
        tenant.value(),
        t0,
        t0.plusSeconds(86400),
        List.of(sched),
        List.of(),
        1L,
        "h");
  }
}
