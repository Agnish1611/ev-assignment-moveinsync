package com.moveinsync.ev.domain.vehicle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class VehicleTest {

  @Test
  @DisplayName("Vehicle construction with valid parameters creates an immutable record")
  void validVehicleCreation() {
    VehicleId id = VehicleId.of("CAB-001");
    Vehicle vehicle = new Vehicle(id, 60_000L, 160L, 50);

    assertThat(vehicle.id()).isEqualTo(id);
    assertThat(vehicle.batteryCapacityWh()).isEqualTo(60_000L);
    assertThat(vehicle.baseConsumptionWhPerKm()).isEqualTo(160L);
    assertThat(vehicle.maxChargeKw()).isEqualTo(50);
  }

  @Test
  @DisplayName("Vehicle rejects negative or zero battery capacity, consumption, or charge power")
  void invalidParametersThrowException() {
    VehicleId id = VehicleId.of("CAB-001");

    assertThatThrownBy(() -> new Vehicle(null, 60_000L, 160L, 50))
        .isInstanceOf(NullPointerException.class);

    assertThatThrownBy(() -> new Vehicle(id, 0L, 160L, 50))
        .isInstanceOf(IllegalArgumentException.class);

    assertThatThrownBy(() -> new Vehicle(id, 60_000L, 0L, 50))
        .isInstanceOf(IllegalArgumentException.class);

    assertThatThrownBy(() -> new Vehicle(id, 60_000L, 160L, 0))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("Standard 15% floor calculation rounds up conservatively")
  void standardFloorCalculation() {
    // 60,000 Wh * 15% = 9,000 Wh exactly
    Vehicle vehicle1 = new Vehicle(VehicleId.of("CAB-1"), 60_000L, 150L, 50);
    assertThat(vehicle1.calculateStandardFloorWh()).isEqualTo(9_000L);

    // 60,001 Wh * 15% = 9,000.15 Wh -> rounds UP to 9,001 Wh
    Vehicle vehicle2 = new Vehicle(VehicleId.of("CAB-2"), 60_001L, 150L, 50);
    assertThat(vehicle2.calculateStandardFloorWh()).isEqualTo(9_001L);
  }

  @Test
  @DisplayName("Robust 18% floor calculation rounds up conservatively")
  void robustFloorCalculation() {
    // 50,000 Wh * 18% = 9,000 Wh exactly
    Vehicle vehicle1 = new Vehicle(VehicleId.of("CAB-1"), 50_000L, 150L, 50);
    assertThat(vehicle1.calculateRobustFloorWh()).isEqualTo(9_000L);

    // 50,001 Wh * 18% = 9,000.18 Wh -> rounds UP to 9,001 Wh
    Vehicle vehicle2 = new Vehicle(VehicleId.of("CAB-2"), 50_001L, 150L, 50);
    assertThat(vehicle2.calculateRobustFloorWh()).isEqualTo(9_001L);
  }

  @Test
  @DisplayName("VehicleId rejects null and blank strings")
  void vehicleIdValidation() {
    assertThatThrownBy(() -> new VehicleId(null)).isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new VehicleId("")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new VehicleId("   ")).isInstanceOf(IllegalArgumentException.class);
  }
}
