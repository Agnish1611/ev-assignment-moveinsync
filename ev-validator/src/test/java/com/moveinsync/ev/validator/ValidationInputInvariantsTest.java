package com.moveinsync.ev.validator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.moveinsync.ev.domain.hub.Charger;
import com.moveinsync.ev.domain.hub.Hub;
import com.moveinsync.ev.domain.location.Location;
import com.moveinsync.ev.domain.tenant.TenantId;
import com.moveinsync.ev.domain.trip.Trip;
import com.moveinsync.ev.domain.trip.TripId;
import com.moveinsync.ev.domain.vehicle.Vehicle;
import com.moveinsync.ev.domain.vehicle.VehicleId;
import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Proof that no public constructor/factory of ValidationInput bypasses Builder validation, and
 * comprehensive verification of each ground-truth layer's invariant enforcement.
 */
class ValidationInputInvariantsTest {

  private final TenantId tenant = TenantId.of("tenant-inv");
  private final Location locA = Location.of("LOC-A", "Depot A");
  private final Location locB = Location.of("LOC-B", "Depot B");
  private final Instant t0 = Instant.parse("2026-10-30T08:00:00Z");

  @Test
  @DisplayName(
      "Proof: ValidationInput has ZERO public constructors (construction strictly via Builder)")
  void proofNoPublicConstructorForValidationInput() {
    Constructor<?>[] publicConstructors = ValidationInput.class.getConstructors();
    assertThat(publicConstructors).isEmpty();

    for (Constructor<?> c : ValidationInput.class.getDeclaredConstructors()) {
      assertThat(Modifier.isPublic(c.getModifiers()))
          .as("Constructor %s must not be public", c)
          .isFalse();
    }
  }

  @Test
  @DisplayName("Vehicle layer: batteryCapacityWh must be strictly positive (> 0)")
  void vehicleRejectsNonPositiveCapacity() {
    assertThatThrownBy(() -> new Vehicle(tenant, VehicleId.of("CAB-01"), 0L, 150L, 50))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Battery capacity must be positive");

    assertThatThrownBy(() -> new Vehicle(tenant, VehicleId.of("CAB-01"), -10_000L, 150L, 50))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Battery capacity must be positive");
  }

  @Test
  @DisplayName("Vehicle layer: baseConsumptionWhPerKm must be strictly positive (> 0)")
  void vehicleRejectsNonPositiveConsumption() {
    assertThatThrownBy(() -> new Vehicle(tenant, VehicleId.of("CAB-01"), 60_000L, 0L, 50))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Base consumption must be positive");

    assertThatThrownBy(() -> new Vehicle(tenant, VehicleId.of("CAB-01"), 60_000L, -5L, 50))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Base consumption must be positive");
  }

  @Test
  @DisplayName("Charger layer: powerKw must be strictly positive (> 0)")
  void chargerRejectsNonPositivePower() {
    assertThatThrownBy(() -> Charger.of("tenant-inv", "CHG-01", "HUB-01", 0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Charger power must be positive");

    assertThatThrownBy(() -> Charger.of("tenant-inv", "CHG-01", "HUB-01", -50))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Charger power must be positive");
  }

  @Test
  @DisplayName("Trip layer: distanceMeters must be non-negative (>= 0)")
  void tripRejectsNegativeDistance() {
    assertThatThrownBy(
            () ->
                new Trip(
                    tenant,
                    TripId.of("TRIP-NEG-DIST"),
                    t0,
                    locA,
                    locB,
                    -1,
                    600,
                    t0.plusSeconds(1800),
                    "EMP-1"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("distanceMeters must be positive");
  }

  @Test
  @DisplayName("Trip layer: planned dropoff cannot exceed latest finish deadline (end >= start)")
  void tripRejectsDropoffExceedingDeadline() {
    // 600s duration from t0 arrives at t0 + 600s; deadline t0 + 500s is invalid
    assertThatThrownBy(
            () ->
                new Trip(
                    tenant,
                    TripId.of("TRIP-LATE"),
                    t0,
                    locA,
                    locB,
                    5000,
                    600,
                    t0.plusSeconds(500),
                    "EMP-1"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("exceeds latestFinishTs");
  }

  @Test
  @DisplayName("Hub layer: timeZoneId must not be null")
  void hubRejectsNullZone() {
    assertThatThrownBy(() -> Hub.of("tenant-inv", "HUB-01", "Central", locA, null, 2))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("Hub timeZone must not be null");
  }

  @Test
  @DisplayName("ValidationInput layer: horizon start must be strictly before horizon end")
  void validationInputRejectsInvertedHorizon() {
    assertThatThrownBy(
            () ->
                ValidationInput.builder().tenantId(tenant).horizon(t0.plusSeconds(100), t0).build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must be strictly before horizonEnd");
  }
}
