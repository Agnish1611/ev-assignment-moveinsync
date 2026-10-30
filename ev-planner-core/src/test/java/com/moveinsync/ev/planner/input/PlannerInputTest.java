package com.moveinsync.ev.planner.input;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.moveinsync.ev.domain.consumption.ConsumptionModel;
import com.moveinsync.ev.domain.distance.DistanceDuration;
import com.moveinsync.ev.domain.distance.DistanceProvider;
import com.moveinsync.ev.domain.hub.Charger;
import com.moveinsync.ev.domain.location.Location;
import com.moveinsync.ev.domain.tenant.TenantId;
import com.moveinsync.ev.domain.vehicle.Vehicle;
import com.moveinsync.ev.domain.vehicle.VehicleId;
import com.moveinsync.ev.planner.PlannerFixtures;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PlannerInputTest {

  @Test
  @DisplayName("The same shift hashes the same way regardless of insertion order")
  void hashIsIndependentOfInsertionOrder() {
    PlannerInput forward = PlannerFixtures.sample();
    PlannerInput reversed =
        PlannerInput.builder()
            .tenantId(PlannerFixtures.TENANT)
            .horizon(PlannerFixtures.START, PlannerFixtures.END)
            .addHub(forward.hubs().get(0))
            .addCharger(forward.chargers().get(0))
            .addCab(forward.cabs().get(1))
            .addCab(forward.cabs().get(0))
            .addTrip(forward.trips().get(1))
            .addTrip(forward.trips().get(0))
            .distanceProvider(forward.distanceProvider())
            .consumptionModel(forward.consumptionModel())
            .build();

    assertThat(reversed.inputHash()).isEqualTo(forward.inputHash());
    assertThat(forward.inputHash()).matches("[0-9a-f]{64}");
    assertThat(forward.cabs().get(0).vehicle().id().value()).isEqualTo("CAB-1");
    assertThat(forward.trips().get(0).id().value()).isEqualTo("TRIP-1");
  }

  @Test
  @DisplayName("A matrix distance provider changes the hash and is accepted")
  void matrixDistanceIsHashed() {
    assertThat(PlannerFixtures.withMatrix().inputHash())
        .isNotEqualTo(PlannerFixtures.sample().inputHash());
    assertThat(PlannerFixtures.withTariffAndOutage().inputHash())
        .isNotEqualTo(PlannerFixtures.sample().inputHash());
  }

  @Test
  @DisplayName("Unknown distance and consumption models are rejected")
  void unknownModelsAreRejected() {
    DistanceProvider anonymous =
        new DistanceProvider() {
          @Override
          public DistanceDuration getDistanceAndDuration(Location from, Location to) {
            return DistanceDuration.of(1, 1);
          }
        };
    assertThatThrownBy(() -> PlannerFixtures.builder().distanceProvider(anonymous).build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("distance provider cannot be hashed");

    ConsumptionModel consumption =
        (vehicle, distanceMeters, travelTime, acOn) -> {
          return 1L;
        };
    assertThatThrownBy(() -> PlannerFixtures.builder().consumptionModel(consumption).build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("consumption model cannot be hashed");
  }

  @Test
  @DisplayName("Cross-tenant, duplicate, and out-of-range snapshots are rejected")
  void invalidSnapshotsAreRejected() {
    TenantId other = TenantId.of("tenant-b");
    assertThatThrownBy(
            () ->
                PlannerFixtures.builder()
                    .addTrip(
                        new com.moveinsync.ev.domain.trip.Trip(
                            other,
                            com.moveinsync.ev.domain.trip.TripId.of("TRIP-9"),
                            PlannerFixtures.START.plusSeconds(600),
                            Location.of("LOC-P", "Pickup"),
                            Location.of("LOC-D", "Drop"),
                            10_000,
                            1_200,
                            PlannerFixtures.START.plusSeconds(4_200),
                            "EMP-9"))
                    .build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("trip tenant");

    assertThatThrownBy(
            () ->
                PlannerFixtures.builder()
                    .addTrip(PlannerFixtures.trip("TRIP-1", PlannerFixtures.START.plusSeconds(600)))
                    .build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("duplicate trip");

    assertThatThrownBy(
            () ->
                PlannerFixtures.builder()
                    .addCab(
                        new CabShift(
                            new Vehicle(
                                PlannerFixtures.TENANT, VehicleId.of("CAB-9"), 60_000L, 150L, 50),
                            Location.of("LOC-HUB", "Hub"),
                            60_001L,
                            PlannerFixtures.START))
                    .build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("battery capacity");

    assertThatThrownBy(() -> PlannerFixtures.builder().idlePowerW(-1).build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("idlePowerW");

    assertThatThrownBy(
            () ->
                PlannerFixtures.builder()
                    .horizon(PlannerFixtures.END, PlannerFixtures.START)
                    .build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("horizonStart");

    assertThatThrownBy(
            () ->
                PlannerFixtures.builder()
                    .addCharger(
                        Charger.of(PlannerFixtures.TENANT.value(), "CHG-9", "HUB-MISSING", 50))
                    .build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("charger hub");

    Instant late = PlannerFixtures.END.plusSeconds(60);
    assertThatThrownBy(
            () -> PlannerFixtures.builder().addTrip(PlannerFixtures.trip("TRIP-9", late)).build())
        .isInstanceOf(IllegalArgumentException.class);
  }
}
