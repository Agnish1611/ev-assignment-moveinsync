package com.moveinsync.ev.sim;

import static org.assertj.core.api.Assertions.assertThat;

import com.moveinsync.ev.domain.tariff.UtcTariffWindow;
import com.moveinsync.ev.domain.trip.Trip;
import com.moveinsync.ev.planner.input.CabShift;
import com.moveinsync.ev.planner.input.PlannerInput;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class ScenarioGeneratorTest {

  @Test
  void eveningScaleIgnoresTheSeedAndMatchesTheCaseStudyFleet() {
    PlannerInput first = ScenarioGenerator.generate(ShiftProfile.EVENING_SCALE, 1L);
    PlannerInput second = ScenarioGenerator.generate(ShiftProfile.EVENING_SCALE, 99L);

    assertThat(first.inputHash()).isEqualTo(second.inputHash());
    assertThat(first.inputHash()).isEqualTo(ScaleScenario.build().inputHash());
    assertThat(first.cabs()).hasSize(ScaleScenario.CAB_COUNT);
    assertThat(first.chargers()).hasSize(ScaleScenario.CHARGER_COUNT);
    assertThat(first.trips()).hasSize(ScaleScenario.TRIP_COUNT);
  }

  @Test
  void theSameSeedRebuildsEachNamedShift() {
    for (ShiftProfile profile : ShiftProfile.values()) {
      PlannerInput first = ScenarioGenerator.generate(profile, 7L);
      PlannerInput second = ScenarioGenerator.generate(profile, 7L);
      assertThat(first.inputHash()).isEqualTo(second.inputHash());
    }
  }

  @Test
  void aDifferentSeedMovesTheTightBatteryShift() {
    String first = ScenarioGenerator.generate(ShiftProfile.TIGHT_BATTERY, 1L).inputHash();
    String second = ScenarioGenerator.generate(ShiftProfile.TIGHT_BATTERY, 2L).inputHash();

    assertThat(first).isNotEqualTo(second);
  }

  @Test
  void tightBatteryLeavesOneCabNearTheFloorAndOneLongTrip() {
    PlannerInput fleet = ScenarioGenerator.generate(ShiftProfile.TIGHT_BATTERY, 4L);
    long lowest = Long.MAX_VALUE;
    long highest = 0L;
    for (CabShift cab : fleet.cabs()) {
      lowest = Math.min(lowest, cab.initialSocWh());
      highest = Math.max(highest, cab.initialSocWh());
    }
    int longest = 0;
    for (Trip trip : fleet.trips()) {
      longest = Math.max(longest, trip.distanceMeters());
    }

    assertThat(fleet.cabs()).hasSize(2);
    assertThat(lowest).isEqualTo(12_000L);
    assertThat(highest).isEqualTo(50_000L);
    assertThat(longest).isEqualTo(40_000);
  }

  @Test
  void chargerScarceHasASinglePlug() {
    PlannerInput fleet = ScenarioGenerator.generate(ShiftProfile.CHARGER_SCARCE, 5L);

    assertThat(fleet.chargers()).hasSize(1);
    assertThat(fleet.hubs().get(0).chargerCount()).isEqualTo(1);
    assertThat(fleet.cabs()).hasSize(3);
    assertThat(fleet.trips()).hasSize(3);
  }

  @Test
  void peakHeavyPutsMoreTripsOnOneInstantThanThereAreCabs() {
    PlannerInput fleet = ScenarioGenerator.generate(ShiftProfile.PEAK_HEAVY, 8L);
    Instant start = fleet.trips().get(0).startTs();

    assertThat(fleet.trips()).hasSizeGreaterThan(fleet.cabs().size());
    assertThat(fleet.trips()).allMatch(trip -> trip.startTs().equals(start));
  }

  @Test
  void dstCrossingRepeatsTheLocalHourAndStretchesTheTariff() {
    PlannerInput fleet = ScenarioGenerator.generate(ShiftProfile.DST_CROSSING, 3L);
    ZoneId newYork = ZoneId.of("America/New_York");
    Trip earlier = fleet.trips().get(0);
    Trip later = fleet.trips().get(1);
    UtcTariffWindow window = fleet.tariffs().get(0).windows().get(0);

    assertThat(fleet.hubs().get(0).timeZone()).isEqualTo(newYork);
    assertThat(earlier.startTs().atZone(newYork).getHour()).isEqualTo(1);
    assertThat(later.startTs().atZone(newYork).getHour()).isEqualTo(1);
    assertThat(earlier.startTs().atZone(newYork).getOffset())
        .isNotEqualTo(later.startTs().atZone(newYork).getOffset());
    assertThat(Duration.between(earlier.startTs(), later.startTs())).isEqualTo(Duration.ofHours(1));
    assertThat(Duration.between(window.startUtc(), window.endUtc())).isEqualTo(Duration.ofHours(3));
    assertThat(window.startUtc()).isAfter(Instant.parse("2026-11-01T04:00:00Z"));
    assertThat(later.latestFinishTs().atZone(ZoneOffset.UTC).getYear()).isEqualTo(2026);
  }
}
