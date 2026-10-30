package com.moveinsync.ev.domain.consumption;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.moveinsync.ev.domain.vehicle.Vehicle;
import com.moveinsync.ev.domain.vehicle.VehicleId;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Direct unit tests and golden arithmetic verification for {@link ConstantConsumptionModel} (F-11).
 */
class ConstantConsumptionModelTest {

  private final Vehicle cab = new Vehicle(VehicleId.of("CAB-01"), 60_000L, 150L, 50);

  @Test
  @DisplayName(
      "Default constructor uses 1.0x traffic factor (10,000 bp) and 1.15x default AC factor (11,500 bp)")
  void defaultConstructorNominalValues() {
    ConstantConsumptionModel model = new ConstantConsumptionModel();

    // 10,000 metres (10 km) at 150 Wh/km nominal consumption:
    // baseWh = (150 * 10,000 + 999) / 1000 = 1,500 Wh
    // AC off: numerator = 1,500 * 10,000 * 10,000 = 150,000,000,000
    // denominator = 100,000,000 -> result = 1,500 Wh
    long energyAcOff = model.calculateEnergyWh(cab, 10_000, Instant.EPOCH, false);
    assertThat(energyAcOff).isEqualTo(1_500L);

    // AC on: numerator = 1,500 * 10,000 * 11,500 = 172,500,000,000
    // denominator = 100,000,000 -> result = 1,725 Wh
    long energyAcOn = model.calculateEnergyWh(cab, 10_000, Instant.EPOCH, true);
    assertThat(energyAcOn).isEqualTo(1_725L);
  }

  @Test
  @DisplayName("Custom constructor rejects zero or negative basis points")
  void constructorValidation() {
    assertThatThrownBy(() -> new ConstantConsumptionModel(0, 11_000))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("trafficFactorBasisPoints must be positive");

    assertThatThrownBy(() -> new ConstantConsumptionModel(-100, 11_000))
        .isInstanceOf(IllegalArgumentException.class);

    assertThatThrownBy(() -> new ConstantConsumptionModel(10_000, 0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("acOnFactorBasisPoints must be positive");

    assertThatThrownBy(() -> new ConstantConsumptionModel(10_000, -500))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("Null arguments are strictly rejected")
  void nullArgumentGuards() {
    ConstantConsumptionModel model = new ConstantConsumptionModel();

    assertThatThrownBy(() -> model.calculateEnergyWh(null, 10_000, Instant.EPOCH, false))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("Vehicle must not be null");

    assertThatThrownBy(() -> model.calculateEnergyWh(cab, 10_000, null, false))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("TravelTime must not be null");
  }

  @Test
  @DisplayName("Zero or negative distance returns 0 Wh energy")
  void zeroAndNegativeDistance() {
    ConstantConsumptionModel model = new ConstantConsumptionModel();
    assertThat(model.calculateEnergyWh(cab, 0, Instant.EPOCH, false)).isZero();
    assertThat(model.calculateEnergyWh(cab, -500, Instant.EPOCH, false)).isZero();
    assertThat(model.calculateEnergyWh(cab, 0, Instant.EPOCH, true)).isZero();
  }

  @Test
  @DisplayName("Golden arithmetic test: 12,345 metres at 175 Wh/km with 1.25x traffic and 1.15x AC")
  void goldenArithmeticCompoundMultiplier() {
    // Hand calculation:
    // Vehicle: 175 Wh/km
    // Distance: 12,345 metres (12.345 km)
    // Traffic factor: 1.25x = 12,500 basis points
    // AC factor: 1.15x = 11,500 basis points
    //
    // Step 1: Base energy calculation
    // baseProduct = 175 * 12,345 = 2,160,375
    // baseWh = (2,160,375 + 999) / 1000 = 2,161 Wh (ceiling integer division)
    //
    // Step 2: Compound multipliers (traffic: 12,500 bp, AC on: 11,500 bp)
    // numerator = 2,161 * 12,500 = 27,012,500
    // numerator = 27,012,500 * 11,500 = 310,643,750,000
    // denominator = 10,000 * 10,000 = 100,000,000
    //
    // Step 3: Conservative ceiling division
    // energyWh = (310,643,750,000 + 100,000,000 - 1) / 100,000,000
    //          = 310,743,749,999 / 100,000,000
    //          = 3,107 Wh
    Vehicle customCab = new Vehicle(VehicleId.of("CAB-GOLD"), 75_000L, 175L, 60);
    ConstantConsumptionModel model = new ConstantConsumptionModel(12_500, 11_500);

    long energy =
        model.calculateEnergyWh(customCab, 12_345, Instant.parse("2026-10-30T10:00:00Z"), true);
    assertThat(energy).isEqualTo(3_107L);
  }

  @Test
  @DisplayName("Golden arithmetic test: 1 metre driving rounds UP conservatively to 1 Wh")
  void goldenArithmeticOneMetreCeiling() {
    // Hand calculation:
    // Vehicle: 150 Wh/km
    // Distance: 1 metre
    // baseProduct = 150 * 1 = 150
    // baseWh = (150 + 999) / 1000 = 1 Wh
    // numerator = 1 * 10,000 * 10,000 = 100,000,000
    // denominator = 100,000,000
    // energyWh = (100,000,000 + 99,999,999) / 100,000,000 = 1 Wh
    ConstantConsumptionModel model = new ConstantConsumptionModel();
    long energy = model.calculateEnergyWh(cab, 1, Instant.EPOCH, false);
    assertThat(energy).isEqualTo(1L);
  }
}
