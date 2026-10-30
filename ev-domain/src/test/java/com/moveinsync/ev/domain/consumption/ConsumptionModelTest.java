package com.moveinsync.ev.domain.consumption;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.moveinsync.ev.domain.tenant.TenantId;
import com.moveinsync.ev.domain.vehicle.Vehicle;
import com.moveinsync.ev.domain.vehicle.VehicleId;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ConsumptionModelTest {

  private final Vehicle cab =
      new Vehicle(TenantId.of("tenant-fixture"), VehicleId.of("CAB-01"), 60_000L, 160L, 50);

  @Test
  @DisplayName("Base consumption calculation without traffic or AC")
  void nominalBaseConsumption() {
    ConstantTrafficFactorProvider traffic = ConstantTrafficFactorProvider.nominal(); // 1.0x
    StandardConsumptionModel model = new StandardConsumptionModel(traffic);

    // 10,000 metres (10 km) at 160 Wh/km = 1,600 Wh
    long energy = model.calculateEnergyWh(cab, 10_000, Instant.EPOCH, false);
    assertThat(energy).isEqualTo(1_600L);

    // 0 distance = 0 Wh
    assertThat(model.calculateEnergyWh(cab, 0, Instant.EPOCH, false)).isEqualTo(0L);

    // 1 metre at 160 Wh/km -> 160 * 1 / 1000 = 0.16 Wh -> rounds UP to 1 Wh
    assertThat(model.calculateEnergyWh(cab, 1, Instant.EPOCH, false)).isEqualTo(1L);
  }

  @Test
  @DisplayName("Traffic multiplier increases energy consumption proportionally")
  void trafficMultiplierCalculation() {
    // 1.25x traffic congestion (12,500 basis points)
    TrafficFactorProvider traffic = new ConstantTrafficFactorProvider(12_500);
    StandardConsumptionModel model = new StandardConsumptionModel(traffic);

    // 10 km base = 1,600 Wh. With 1.25x traffic = 2,000 Wh
    long energy = model.calculateEnergyWh(cab, 10_000, Instant.EPOCH, false);
    assertThat(energy).isEqualTo(2_000L);
  }

  @Test
  @DisplayName("Air conditioning multiplier increases consumption by configured percentage")
  void airConditioningMultiplierCalculation() {
    ConstantTrafficFactorProvider traffic = ConstantTrafficFactorProvider.nominal();
    // 1.15x AC load (11,500 basis points)
    StandardConsumptionModel model = new StandardConsumptionModel(traffic, 11_500);

    // 10 km base = 1,600 Wh. AC on (1.15x) = 1,840 Wh
    long energyAcOn = model.calculateEnergyWh(cab, 10_000, Instant.EPOCH, true);
    assertThat(energyAcOn).isEqualTo(1_840L);

    // AC off (1.00x) = 1,600 Wh
    long energyAcOff = model.calculateEnergyWh(cab, 10_000, Instant.EPOCH, false);
    assertThat(energyAcOff).isEqualTo(1_600L);
  }

  @Test
  @DisplayName("Combined base × traffic × AC matches expected compound multiplier")
  void combinedTrafficAndAcCalculation() {
    // 1.25x traffic, 1.15x AC
    TrafficFactorProvider traffic = new ConstantTrafficFactorProvider(12_500);
    StandardConsumptionModel model = new StandardConsumptionModel(traffic, 11_500);

    // 10 km base = 1,600 Wh
    // 1,600 * 1.25 * 1.15 = 2,300 Wh
    long energy = model.calculateEnergyWh(cab, 10_000, Instant.EPOCH, true);
    assertThat(energy).isEqualTo(2_300L);
  }

  @Test
  @DisplayName("TimeOfDayTrafficFactorProvider applies peak congestion based on time of day")
  void timeOfDayTrafficFactor() {
    ZoneId zone = ZoneId.of("Asia/Kolkata");
    TimeOfDayTrafficFactorProvider provider =
        new TimeOfDayTrafficFactorProvider(
            zone,
            LocalTime.of(8, 0),
            LocalTime.of(11, 0),
            12_500, // 1.25x morning peak
            LocalTime.of(17, 0),
            LocalTime.of(21, 0),
            13_000, // 1.30x evening peak
            10_000 // 1.00x off peak
            );

    // 09:00 IST = morning peak (1.25x = 12,500 bp)
    Instant morning = Instant.parse("2026-10-30T03:30:00Z");
    assertThat(provider.getTrafficFactorBasisPoints(morning)).isEqualTo(12_500);

    // 18:00 IST = evening peak (1.30x = 13,000 bp)
    Instant evening = Instant.parse("2026-10-30T12:30:00Z");
    assertThat(provider.getTrafficFactorBasisPoints(evening)).isEqualTo(13_000);

    // 14:00 IST = off peak (1.00x = 10,000 bp)
    Instant afternoon = Instant.parse("2026-10-30T08:30:00Z");
    assertThat(provider.getTrafficFactorBasisPoints(afternoon)).isEqualTo(10_000);
  }

  @Test
  @DisplayName("ConsumptionModel rejects null arguments")
  void nullValidation() {
    StandardConsumptionModel model =
        new StandardConsumptionModel(ConstantTrafficFactorProvider.nominal());
    assertThatThrownBy(() -> model.calculateEnergyWh(null, 10_000, Instant.EPOCH, false))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> model.calculateEnergyWh(cab, 10_000, null, false))
        .isInstanceOf(NullPointerException.class);
  }
}
