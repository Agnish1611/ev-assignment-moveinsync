package com.moveinsync.ev.validator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.moveinsync.ev.domain.consumption.ConstantConsumptionModel;
import com.moveinsync.ev.domain.distance.ConstantSpeedDistanceProvider;
import com.moveinsync.ev.domain.hub.Charger;
import com.moveinsync.ev.domain.hub.Hub;
import com.moveinsync.ev.domain.location.Location;
import com.moveinsync.ev.domain.schedule.FleetPlan;
import com.moveinsync.ev.domain.tenant.TenantId;
import com.moveinsync.ev.domain.trip.Trip;
import com.moveinsync.ev.domain.trip.TripId;
import com.moveinsync.ev.domain.vehicle.Vehicle;
import com.moveinsync.ev.domain.vehicle.VehicleId;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Tenant is mandatory on ground truth and mixed tenants are rejected. */
class TenantBoundaryTest {

  @Test
  @DisplayName("ValidationInput with mixed tenants yields TENANT_MISMATCH")
  void mixedTenantsYieldTenantMismatch() {
    TenantId tenantA = TenantId.of("tenant-a");
    TenantId tenantB = TenantId.of("tenant-b");
    Instant t0 = Instant.parse("2026-10-30T08:00:00Z");
    Location hubLoc = Location.of("LOC-HUB", "Hub");
    Hub hub = Hub.of(tenantA.value(), "HUB-1", "Hub", hubLoc, ZoneId.of("UTC"), 1);
    Hub otherHub = Hub.of(tenantB.value(), "HUB-2", "Other", hubLoc, ZoneId.of("UTC"), 1);
    Charger charger = Charger.of(tenantB.value(), "CHG-1", "HUB-1", 50);
    Vehicle vehicle = new Vehicle(tenantA, VehicleId.of("CAB-1"), 60_000L, 150L, 50);
    Vehicle otherVehicle = new Vehicle(tenantB, VehicleId.of("CAB-2"), 60_000L, 150L, 50);
    Trip trip = trip(tenantB, "TRIP-1", t0);
    ValidationInput input =
        ValidationInput.builder()
            .tenantId(tenantA)
            .horizon(t0, t0.plusSeconds(7_200))
            .addHub(hub)
            .addHub(otherHub)
            .addCharger(charger)
            .addVehicle(vehicle, hubLoc, 50_000L)
            .addVehicle(otherVehicle, hubLoc, 50_000L)
            .addTrip(trip)
            .distanceProvider(new ConstantSpeedDistanceProvider(5_000, 10))
            .consumptionModel(new ConstantConsumptionModel())
            .build();
    FleetPlan plan =
        new FleetPlan(
            "P-MIX", tenantA, t0, t0.plusSeconds(7_200), List.of(), List.of(), 1L, "hash");

    ValidationReport report = new PlanValidator().validate(input, plan);

    assertThat(report.isValid()).isFalse();
    assertThat(report.violationsFor(RuleId.TENANT_MISMATCH)).hasSize(4);
  }

  @Test
  @DisplayName("Report cap, root-cause key, and context accessors are live")
  void reportCapAndContextAccessorsAreUsed() {
    TenantId tenant = TenantId.of("tenant-a");
    Instant t0 = Instant.parse("2026-10-30T08:00:00Z");
    Location hubLoc = Location.of("LOC-HUB", "Hub");
    Hub hub = Hub.of(tenant.value(), "HUB-1", "Hub", hubLoc, ZoneId.of("UTC"), 1);
    Charger charger = Charger.of(tenant.value(), "CHG-1", "HUB-1", 50);
    ValidationInput input =
        ValidationInput.builder()
            .tenantId(tenant)
            .horizon(t0, t0.plusSeconds(7_200))
            .addHub(hub)
            .addCharger(charger)
            .addTrip(trip(tenant, "TRIP-1", t0))
            .addTrip(trip(tenant, "TRIP-2", t0.plusSeconds(3_600)))
            .distanceProvider(new ConstantSpeedDistanceProvider(5_000, 10))
            .consumptionModel(new ConstantConsumptionModel())
            .maxViolationsReportCap(1)
            .build();
    FleetPlan plan =
        new FleetPlan("P-CAP", tenant, t0, t0.plusSeconds(7_200), List.of(), List.of(), 1L, "hash");

    ValidationReport report = new PlanValidator().validate(input, plan);

    assertThat(report.violations()).hasSize(1);
    assertThat(report.isTruncated()).isTrue();
    assertThat(report.totalViolationsCount()).isGreaterThan(1);

    PlanViolation keyed =
        PlanViolation.ofGeneral(RuleId.TRIP_OMITTED, "omitted").withRootCauseKey("cause-1");
    assertThat(keyed.rootCauseKey()).contains("cause-1");

    ValidationContext context =
        ValidationContext.builder()
            .acOn(true)
            .addHub(hub)
            .addCharger(charger)
            .distanceProvider(new ConstantSpeedDistanceProvider(5_000, 10))
            .consumptionModel(new ConstantConsumptionModel())
            .build();
    assertThat(context.acOn()).isTrue();
    assertThat(context.chargers()).containsKey(charger.id());
  }

  @Test
  @DisplayName("ValidationInput builder rejects a missing tenant")
  void builderRequiresTenant() {
    assertThatThrownBy(
            () ->
                ValidationInput.builder()
                    .distanceProvider(new ConstantSpeedDistanceProvider(5_000, 10))
                    .consumptionModel(new ConstantConsumptionModel())
                    .build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("tenantId");
  }

  private static Trip trip(TenantId tenant, String tripId, Instant t0) {
    return new Trip(
        tenant,
        TripId.of(tripId),
        t0.plusSeconds(600),
        Location.of("LOC-P", "Pickup"),
        Location.of("LOC-D", "Drop"),
        10_000,
        1_200,
        t0.plusSeconds(3_600),
        "EMP-1");
  }
}
