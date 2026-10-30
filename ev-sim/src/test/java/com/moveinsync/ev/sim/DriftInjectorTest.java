package com.moveinsync.ev.sim;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.moveinsync.ev.domain.consumption.ConstantConsumptionModel;
import com.moveinsync.ev.planner.input.CabShift;
import com.moveinsync.ev.planner.input.PlannerInput;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

class DriftInjectorTest {

  @Test
  void theSameSeedRebuildsTrafficNoiseOutlierAndSilence() {
    PlannerInput fleet = ScenarioGenerator.generate(ShiftProfile.TIGHT_BATTERY, 4L);
    DriftSample first = DriftInjector.inject(fleet, 11L);
    DriftSample second = DriftInjector.inject(fleet, 11L);

    assertThat(first).isEqualTo(second);
    assertThat(first.readings()).hasSize(fleet.cabs().size());
    assertThat(first.trafficFactorBasisPoints()).isIn(8_000, 10_000, 12_000, 15_000);
    assertThat(first.readings())
        .filteredOn(reading -> reading.kind() == CabReading.Kind.SILENT)
        .singleElement()
        .extracting(CabReading::reportedSocWh)
        .isEqualTo(OptionalLong.empty());
    CabReading outlier =
        first.readings().stream()
            .filter(reading -> reading.kind() == CabReading.Kind.OUTLIER)
            .findFirst()
            .orElseThrow();
    assertThat(outlier.reportedSocWh().getAsLong()).isGreaterThan(60_000L);
    assertThat(first.readings())
        .filteredOn(reading -> reading.kind() == CabReading.Kind.NOISE)
        .allSatisfy(reading -> assertInsidePack(fleet, reading));
  }

  @Test
  void aDifferentSeedChangesTheEveningSample() {
    PlannerInput fleet = ScenarioGenerator.generate(ShiftProfile.EVENING_SCALE, 1L);
    DriftSample first = DriftInjector.inject(fleet, 1L);
    DriftSample second = DriftInjector.inject(fleet, 2L);

    assertThat(first).isNotEqualTo(second);
    assertThat(first.readings()).noneMatch(reading -> reading.cabId().contains("@"));
  }

  @Test
  void trafficRebuildsTheFleetAndRejectsANonPositiveFactor() {
    PlannerInput fleet = ScenarioGenerator.generate(ShiftProfile.PEAK_HEAVY, 8L);
    PlannerInput shifted = DriftInjector.withTraffic(fleet, 12_000);

    assertThat(shifted.consumptionModel()).isInstanceOf(ConstantConsumptionModel.class);
    ConstantConsumptionModel model = (ConstantConsumptionModel) shifted.consumptionModel();
    assertThat(model.trafficFactorBasisPoints()).isEqualTo(12_000);
    assertThat(shifted.inputHash()).isNotEqualTo(fleet.inputHash());
    assertThat(shifted.trips()).hasSize(fleet.trips().size());
    assertThatThrownBy(() -> DriftInjector.withTraffic(fleet, 0))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void oneCabCannotCarryBothASilenceAndAnOutlier() {
    PlannerInput fleet = ScenarioGenerator.generate(ShiftProfile.CHARGER_SCARCE, 5L);
    PlannerInput lonely =
        PlannerInput.builder()
            .tenantId(fleet.tenantId())
            .horizon(fleet.horizonStart(), fleet.horizonEnd())
            .addHub(fleet.hubs().get(0))
            .addCharger(fleet.chargers().get(0))
            .addCab(fleet.cabs().get(0))
            .addTrip(fleet.trips().get(0))
            .distanceProvider(fleet.distanceProvider())
            .consumptionModel(fleet.consumptionModel())
            .build();

    assertThatThrownBy(() -> DriftInjector.inject(lonely, 1L))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static void assertInsidePack(PlannerInput fleet, CabReading reading) {
    long capacity = 0L;
    for (CabShift cab : fleet.cabs()) {
      if (cab.vehicle().id().value().equals(reading.cabId())) {
        capacity = cab.vehicle().batteryCapacityWh();
      }
    }
    long reported = reading.reportedSocWh().orElseThrow();
    assertThat(reported).isBetween(0L, capacity);
  }
}
