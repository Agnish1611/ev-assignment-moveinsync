package com.moveinsync.ev.validator;

import static org.assertj.core.api.Assertions.assertThat;

import com.moveinsync.ev.domain.consumption.ConstantConsumptionModel;
import com.moveinsync.ev.domain.distance.DistanceDuration;
import com.moveinsync.ev.domain.distance.MatrixDistanceProvider;
import com.moveinsync.ev.domain.hub.Charger;
import com.moveinsync.ev.domain.hub.Hub;
import com.moveinsync.ev.domain.location.Location;
import com.moveinsync.ev.domain.schedule.ChargingSession;
import com.moveinsync.ev.domain.schedule.FleetPlan;
import com.moveinsync.ev.domain.schedule.VehicleSchedule;
import com.moveinsync.ev.domain.tenant.TenantId;
import com.moveinsync.ev.domain.vehicle.Vehicle;
import com.moveinsync.ev.domain.vehicle.VehicleId;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

class PlanValidatorPropertyTest {

  @Provide
  Arbitrary<Long> outOfBoundsTargetSocs() {
    return Arbitraries.longs().between(60_001L, 200_000L);
  }

  @Property
  void malformedTargetSocNeverThrows(@ForAll("outOfBoundsTargetSocs") long invalidTargetSoc) {
    TenantId tenantId = TenantId.of("tenant-property");
    Location hubLoc = Location.of("LOC-HUB", "Hub North");
    Hub hub = Hub.of(tenantId.value(), "HUB-1", "Hub North", hubLoc, ZoneId.of("UTC"), 1);
    Charger charger = Charger.of(tenantId.value(), "CHG-1", "HUB-1", 50);
    Vehicle vehicle = new Vehicle(tenantId, VehicleId.of("CAB-01"), 60_000L, 150L, 50);
    Instant t0 = Instant.parse("2026-10-30T08:00:00Z");

    Map<MatrixDistanceProvider.LocationPair, DistanceDuration> matrix = Map.of();
    ValidationInput input =
        ValidationInput.builder()
            .tenantId(tenantId)
            .horizon(t0, t0.plusSeconds(86400))
            .addHub(hub)
            .addCharger(charger)
            .addVehicle(vehicle, hubLoc, 50_000L)
            .distanceProvider(new MatrixDistanceProvider(matrix))
            .consumptionModel(new ConstantConsumptionModel())
            .build();

    PlanValidator validator = new PlanValidator();

    ChargingSession session =
        new ChargingSession(
            "SESS-FUZZ",
            vehicle.id(),
            hub.id(),
            charger.id(),
            t0.plusSeconds(100),
            t0.plusSeconds(2000),
            40_000L,
            invalidTargetSoc,
            500_000L);
    VehicleSchedule sched = new VehicleSchedule(vehicle, hubLoc, 50_000L, t0, List.of(session));

    FleetPlan candidate =
        new FleetPlan(
            "P-FUZZ", tenantId, t0, t0.plusSeconds(3600), List.of(sched), List.of(), 1L, "hash");

    ValidationReport report = validator.validate(input, candidate);
    assertThat(report.isValid()).isFalse();
    assertThat(report.violationsFor(RuleId.SOC_OUT_OF_RANGE)).isNotEmpty();
  }
}
