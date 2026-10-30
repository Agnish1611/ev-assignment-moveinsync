package com.moveinsync.ev.planner.construct;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.moveinsync.ev.domain.consumption.ConstantConsumptionModel;
import com.moveinsync.ev.domain.distance.ConstantSpeedDistanceProvider;
import com.moveinsync.ev.domain.hub.Charger;
import com.moveinsync.ev.domain.hub.Hub;
import com.moveinsync.ev.domain.location.Location;
import com.moveinsync.ev.domain.tariff.TariffSchedule;
import com.moveinsync.ev.domain.tariff.UtcTariffWindow;
import com.moveinsync.ev.domain.trip.Trip;
import com.moveinsync.ev.planner.PlannerFixtures;
import com.moveinsync.ev.planner.input.PlannerInput;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;

class FleetLowerBoundTest {

  @Test
  void anEmptyShiftHasAZeroBound() {
    FleetLowerBound bound = FleetLowerBound.of(PlannerFixtures.shell().build());

    assertThat(bound.intrinsicBackups()).isZero();
    assertThat(bound.overlapBackups()).isZero();
    assertThat(bound.energyBackups()).isZero();
    assertThat(bound.minimumCostMicros()).isZero();
    assertThat(bound.backups()).isZero();
  }

  @Test
  void aTripLongerThanTheBatteryIsIntrinsic() {
    Trip trip =
        PlannerFixtures.trip(
            "TRIP-LONG",
            PlannerFixtures.START.plusSeconds(600),
            PlannerFixtures.depot(),
            Location.of("LOC-D", "Drop"),
            500_000);
    PlannerInput fleet =
        PlannerFixtures.shell().addCab(PlannerFixtures.cab("CAB-1", 50_000L)).addTrip(trip).build();

    FleetLowerBound bound = FleetLowerBound.of(fleet);

    assertThat(bound.intrinsicBackups()).isEqualTo(1);
    assertThat(bound.energyBackups()).isEqualTo(1);
    assertThat(bound.backups()).isEqualTo(1);
  }

  @Test
  void overlappingTripsNeedAnotherCab() {
    Trip first = PlannerFixtures.trip("TRIP-A", PlannerFixtures.START.plusSeconds(600));
    Trip second = PlannerFixtures.trip("TRIP-B", PlannerFixtures.START.plusSeconds(600));
    PlannerInput fleet =
        PlannerFixtures.shell()
            .addCab(PlannerFixtures.cab("CAB-1", 50_000L))
            .addTrip(first)
            .addTrip(second)
            .build();

    assertThat(FleetLowerBound.of(fleet).overlapBackups()).isEqualTo(1);
  }

  @Test
  void aTripThatEndsAsTheNextStartsDoesNotOverlap() {
    Trip first = PlannerFixtures.trip("TRIP-A", PlannerFixtures.START.plusSeconds(600));
    Trip second = PlannerFixtures.trip("TRIP-B", PlannerFixtures.START.plusSeconds(1_800));
    PlannerInput fleet =
        PlannerFixtures.shell()
            .addCab(PlannerFixtures.cab("CAB-1", 50_000L))
            .addTrip(first)
            .addTrip(second)
            .build();

    assertThat(FleetLowerBound.of(fleet).overlapBackups()).isZero();
  }

  @Test
  void energyThatDoesNotFitInAOneSecondHorizonIsABackup() {
    PlannerInput fleet = shortShift(1, 1, false);

    FleetLowerBound bound = FleetLowerBound.of(fleet);

    assertThat(bound.intrinsicBackups()).isZero();
    assertThat(bound.energyBackups()).isEqualTo(1);
    assertThat(bound.minimumCostMicros()).isZero();
  }

  @Test
  void gridEnergyIsPricedAtTheCheapestTariffRoundedDown() {
    PlannerInput fleet = shortShift(3_600, 50, true);

    FleetLowerBound bound = FleetLowerBound.of(fleet);

    assertThat(bound.energyBackups()).isZero();
    assertThat(bound.minimumCostMicros()).isEqualTo(4_500_000L);
  }

  @Test
  void anOverflowingTariffProductStillReturnsAFiniteFloor() {
    Instant start = PlannerFixtures.START;
    Instant end = start.plusSeconds(3_600);
    Location depot = PlannerFixtures.depot();
    Hub hub = Hub.of(PlannerFixtures.TENANT.value(), "HUB-1", "Hub", depot, ZoneId.of("UTC"), 1);
    Trip trip =
        new Trip(
            PlannerFixtures.TENANT,
            com.moveinsync.ev.domain.trip.TripId.of("TRIP-1"),
            start,
            Location.of("LOC-P", "Pickup"),
            Location.of("LOC-D", "Drop"),
            10_000,
            600,
            start.plusSeconds(600),
            "EMP-1");
    PlannerInput fleet =
        PlannerInput.builder()
            .tenantId(PlannerFixtures.TENANT)
            .horizon(start, end)
            .addHub(hub)
            .addCharger(Charger.of(PlannerFixtures.TENANT.value(), "CHG-1", "HUB-1", 50))
            .addCab(PlannerFixtures.cab("CAB-1", 9_000L))
            .addTrip(trip)
            .addTariff(
                new TariffSchedule(
                    hub.id(), List.of(new UtcTariffWindow(hub.id(), start, end, Long.MAX_VALUE))))
            .distanceProvider(new ConstantSpeedDistanceProvider(5_000, 10))
            .consumptionModel(new ConstantConsumptionModel())
            .build();

    assertThat(FleetLowerBound.of(fleet).minimumCostMicros()).isEqualTo(Long.MAX_VALUE / 1000L);
  }

  @Test
  void nullAndNegativeBoundsAreRejected() {
    assertThatThrownBy(() -> FleetLowerBound.of(null)).isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new FleetLowerBound(-1, 0, 0, 0L))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new FleetLowerBound(0, 0, 0, -1L))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static PlannerInput shortShift(int horizonSeconds, int chargerKw, boolean tariff) {
    Instant start = PlannerFixtures.START;
    Instant end = start.plusSeconds(horizonSeconds);
    Location depot = PlannerFixtures.depot();
    Hub hub = Hub.of(PlannerFixtures.TENANT.value(), "HUB-1", "Hub", depot, ZoneId.of("UTC"), 1);
    Trip trip =
        new Trip(
            PlannerFixtures.TENANT,
            com.moveinsync.ev.domain.trip.TripId.of("TRIP-1"),
            start,
            Location.of("LOC-P", "Pickup"),
            Location.of("LOC-D", "Drop"),
            10_000,
            horizonSeconds == 1 ? 1 : 600,
            start.plusSeconds(horizonSeconds == 1 ? 1 : 600),
            "EMP-1");
    PlannerInput.Builder builder =
        PlannerInput.builder()
            .tenantId(PlannerFixtures.TENANT)
            .horizon(start, end)
            .addHub(hub)
            .addCharger(Charger.of(PlannerFixtures.TENANT.value(), "CHG-1", "HUB-1", chargerKw))
            .addCab(PlannerFixtures.cab("CAB-1", 9_000L))
            .addTrip(trip)
            .distanceProvider(new ConstantSpeedDistanceProvider(5_000, 10))
            .consumptionModel(new ConstantConsumptionModel());
    if (tariff) {
      builder.addTariff(
          new TariffSchedule(
              hub.id(),
              List.of(
                  new UtcTariffWindow(hub.id(), start, end, 5_000_000L),
                  new UtcTariffWindow(hub.id(), start, end, 2_000_000L))));
    }
    return builder.build();
  }
}
