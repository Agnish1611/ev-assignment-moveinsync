package com.moveinsync.ev.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.moveinsync.ev.domain.charging.ChargingCurve;
import com.moveinsync.ev.domain.consumption.ConstantTrafficFactorProvider;
import com.moveinsync.ev.domain.consumption.StandardConsumptionModel;
import com.moveinsync.ev.domain.consumption.TimeOfDayTrafficFactorProvider;
import com.moveinsync.ev.domain.distance.ConstantSpeedDistanceProvider;
import com.moveinsync.ev.domain.distance.DistanceDuration;
import com.moveinsync.ev.domain.hub.Charger;
import com.moveinsync.ev.domain.hub.ChargerId;
import com.moveinsync.ev.domain.hub.ChargerStatus;
import com.moveinsync.ev.domain.hub.Hub;
import com.moveinsync.ev.domain.hub.HubId;
import com.moveinsync.ev.domain.hub.OutageWindow;
import com.moveinsync.ev.domain.location.Location;
import com.moveinsync.ev.domain.schedule.ChargingSession;
import com.moveinsync.ev.domain.schedule.FleetPlan;
import com.moveinsync.ev.domain.schedule.TripAssignment;
import com.moveinsync.ev.domain.schedule.UnassignedReasonCode;
import com.moveinsync.ev.domain.schedule.UnassignedTrip;
import com.moveinsync.ev.domain.schedule.VehicleSchedule;
import com.moveinsync.ev.domain.tariff.TariffSchedule;
import com.moveinsync.ev.domain.tariff.TariffWindow;
import com.moveinsync.ev.domain.tariff.UtcTariffWindow;
import com.moveinsync.ev.domain.tenant.TenantId;
import com.moveinsync.ev.domain.trip.Trip;
import com.moveinsync.ev.domain.trip.TripId;
import com.moveinsync.ev.domain.vehicle.Vehicle;
import com.moveinsync.ev.domain.vehicle.VehicleId;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Comprehensive coverage suite for ev-domain value objects and business models. */
class DomainCoverageTest {

  @Test
  @DisplayName("ChargingCurve boundary validations, getters, and slow segment start")
  void chargingCurveCoverage() {
    ChargingCurve custom = new ChargingCurve(70, 40);
    assertThat(custom.fastThresholdPercent()).isEqualTo(70);
    assertThat(custom.slowRatePercent()).isEqualTo(40);

    assertThatThrownBy(() -> new ChargingCurve(0, 50)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ChargingCurve(100, 50))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ChargingCurve(80, 0)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ChargingCurve(80, 100))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> custom.calculateThresholdSocWh(0))
        .isInstanceOf(IllegalArgumentException.class);

    // Target SoC starting in slow segment
    long targetInSlow = custom.calculateTargetSocWh(50_000L, 1000L, 60_000L, 50);
    assertThat(targetInSlow).isGreaterThan(50_000L);

    // Target SoC with zero/negative duration returns startSoc
    assertThat(custom.calculateTargetSocWh(30_000L, 0L, 60_000L, 50)).isEqualTo(30_000L);
    assertThat(custom.calculateTargetSocWh(30_000L, -5L, 60_000L, 50)).isEqualTo(30_000L);

    // Validation guards in calculateTargetSocWh
    assertThatThrownBy(() -> custom.calculateTargetSocWh(10_000L, 100L, 0L, 50))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> custom.calculateTargetSocWh(10_000L, 100L, 50_000L, 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> custom.calculateTargetSocWh(-1L, 100L, 50_000L, 50))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> custom.calculateTargetSocWh(60_001L, 100L, 60_000L, 50))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("TariffWindow and UtcTariffWindow conversions, overlaps, and string formatting")
  void tariffWindowAndUtcCoverage() {
    HubId hubId = HubId.of("H-1");
    TariffWindow window =
        new TariffWindow(hubId, LocalTime.of(8, 0), LocalTime.of(12, 0), 150_000L);
    assertThat(window.hubId()).isEqualTo(hubId);
    assertThat(window.localStartTime()).isEqualTo(LocalTime.of(8, 0));
    assertThat(window.localEndTime()).isEqualTo(LocalTime.of(12, 0));
    assertThat(window.ratePerKwhMicros()).isEqualTo(150_000L);

    ZoneId zone = ZoneId.of("UTC");
    LocalDate date = LocalDate.of(2026, 10, 30);
    UtcTariffWindow utcWin = window.toUtcWindow(date, zone);
    assertThat(utcWin.hubId()).isEqualTo(hubId);
    assertThat(utcWin.startUtc()).isEqualTo(date.atTime(8, 0).atZone(zone).toInstant());
    assertThat(utcWin.endUtc()).isEqualTo(date.atTime(12, 0).atZone(zone).toInstant());
    assertThat(utcWin.ratePerKwhMicros()).isEqualTo(150_000L);

    Instant tBefore = utcWin.startUtc().minusSeconds(100);
    Instant tDuring = utcWin.startUtc().plusSeconds(100);
    assertThat(utcWin.contains(tDuring)).isTrue();
    assertThat(utcWin.contains(tBefore)).isFalse();
    assertThat(utcWin.contains(utcWin.endUtc())).isFalse();
    assertThat(utcWin.overlaps(tBefore, tDuring)).isTrue();
    assertThat(utcWin.overlaps(tBefore, utcWin.startUtc())).isFalse();

    List<UtcTariffWindow> multi = window.toUtcWindows(date, date.plusDays(2), zone);
    assertThat(multi).hasSize(3);

    TariffSchedule schedule = new TariffSchedule(hubId, List.of(utcWin));
    assertThat(schedule.windows()).hasSize(1);
    assertThat(schedule.hubId()).isEqualTo(hubId);
    assertThat(schedule.findTariffAt(tDuring)).isPresent();
    assertThat(schedule.findTariffAt(tBefore)).isEmpty();
    assertThat(schedule.calculateCostMicros(utcWin.startUtc(), utcWin.endUtc(), 0L)).isZero();
    assertThat(schedule.calculateCostMicros(utcWin.startUtc(), utcWin.endUtc(), 10_000L))
        .isPositive();
    assertThatThrownBy(
            () -> schedule.calculateCostMicros(utcWin.endUtc(), utcWin.startUtc(), 1000L))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("Hub and Charger outage windows, availability, and mutation methods")
  void hubAndChargerCoverage() {
    Location loc = Location.of("LOC-1", "Depot");
    Hub hub = Hub.of("T-1", "HUB-1", "Central", loc, ZoneId.of("UTC"), 2);
    assertThat(hub.id()).isEqualTo(HubId.of("HUB-1"));
    assertThat(hub.name()).isEqualTo("Central");
    assertThat(hub.chargerCount()).isEqualTo(2);

    Instant t0 = Instant.parse("2026-10-30T10:00:00Z");
    OutageWindow outage = new OutageWindow(t0, t0.plusSeconds(3600), "Maintenance");
    assertThat(outage.overlaps(t0.minusSeconds(10), t0.plusSeconds(10))).isTrue();
    assertThat(outage.overlaps(t0.plusSeconds(4000), t0.plusSeconds(5000))).isFalse();

    Charger chg = Charger.of("T-1", "CHG-1", "HUB-1", 50);
    assertThat(chg.isAvailableDuring(t0, t0.plusSeconds(600))).isTrue();
    assertThat(chg.calculateEffectivePowerKw(40)).isEqualTo(40);
    assertThat(chg.calculateEffectivePowerKw(100)).isEqualTo(50);
    assertThatThrownBy(() -> chg.calculateEffectivePowerKw(0))
        .isInstanceOf(IllegalArgumentException.class);

    Charger withOutage = chg.withOutages(List.of(outage));
    assertThat(withOutage.isAvailableDuring(t0.plusSeconds(10), t0.plusSeconds(20))).isFalse();
    assertThat(withOutage.isAvailableDuring(t0.plusSeconds(5000), t0.plusSeconds(6000))).isTrue();

    Charger faulty = chg.withStatus(ChargerStatus.FAULTY);
    assertThat(faulty.isAvailableDuring(t0, t0.plusSeconds(100))).isFalse();
  }

  @Test
  @DisplayName("Consumption and traffic factor provider coverage")
  void consumptionAndTrafficCoverage() {
    TimeOfDayTrafficFactorProvider tod =
        new TimeOfDayTrafficFactorProvider(
            ZoneId.of("UTC"),
            LocalTime.of(8, 0),
            LocalTime.of(11, 0),
            13_000,
            LocalTime.of(17, 0),
            LocalTime.of(20, 0),
            14_000,
            10_000);
    Instant morningPeak = Instant.parse("2026-10-30T09:00:00Z");
    int bp = tod.getTrafficFactorBasisPoints(morningPeak);
    assertThat(bp).isEqualTo(13_000);

    ConstantTrafficFactorProvider nominal = ConstantTrafficFactorProvider.nominal();
    assertThat(nominal.getTrafficFactorBasisPoints(morningPeak)).isEqualTo(10_000);

    TimeOfDayTrafficFactorProvider eveningShift =
        TimeOfDayTrafficFactorProvider.defaultEveningShift(ZoneId.of("UTC"));
    assertThat(eveningShift.getTrafficFactorBasisPoints(Instant.parse("2026-10-30T18:00:00Z")))
        .isEqualTo(13_000);
    assertThat(eveningShift.getTrafficFactorBasisPoints(Instant.parse("2026-10-30T02:00:00Z")))
        .isEqualTo(10_000);

    assertThatThrownBy(
            () ->
                new TimeOfDayTrafficFactorProvider(
                    ZoneId.of("UTC"),
                    LocalTime.of(8, 0),
                    LocalTime.of(11, 0),
                    -1,
                    LocalTime.of(17, 0),
                    LocalTime.of(20, 0),
                    14_000,
                    10_000))
        .isInstanceOf(IllegalArgumentException.class);

    StandardConsumptionModel model = new StandardConsumptionModel(tod, 11_500);
    Vehicle cab = new Vehicle(TenantId.of("T-1"), VehicleId.of("CAB-01"), 60_000L, 160L, 50);
    long energy = model.calculateEnergyWh(cab, 10_000, morningPeak, true);
    assertThat(energy).isGreaterThan(1600L);
    assertThat(model.calculateEnergyWh(cab, 0, morningPeak, true)).isZero();
    assertThat(model.calculateEnergyWh(cab, -5, morningPeak, true)).isZero();
  }

  @Test
  @DisplayName("Domain entities: Schedule, Plan, TripAssignment, ChargingSession, and Marker")
  void entitiesAndScheduleCoverage() {
    DomainMarker marker = new DomainMarker(42);
    assertThat(marker.version()).isEqualTo(42);

    TenantId tenant = TenantId.of("T-1");
    Location locA = Location.of("LOC-A", "Pickup");
    Location locB = Location.of("LOC-B", "Dropoff");
    Vehicle cab = new Vehicle(tenant, VehicleId.of("CAB-1"), 60_000L, 150L, 50);
    Instant t0 = Instant.parse("2026-10-30T08:00:00Z");

    Trip trip =
        new Trip(
            tenant,
            TripId.of("TRIP-1"),
            t0.plusSeconds(600),
            locA,
            locB,
            10_000,
            1200,
            t0.plusSeconds(3600),
            "EMP");
    TripAssignment assign =
        new TripAssignment(
            trip,
            cab.id(),
            t0.plusSeconds(600),
            t0.plusSeconds(1800),
            5000,
            600,
            750L,
            49_250L,
            47_750L);
    assertThat(assign.activityStartTime()).isEqualTo(t0);
    assertThat(assign.activityEndTime()).isEqualTo(t0.plusSeconds(1800));

    ChargingSession session =
        new ChargingSession(
            "S-1",
            cab.id(),
            HubId.of("H-1"),
            ChargerId.of("C-1"),
            t0.plusSeconds(2400),
            t0.plusSeconds(3600),
            47_750L,
            55_000L,
            800_000L);
    assertThat(session.activityStartTime()).isEqualTo(t0.plusSeconds(2400));
    assertThat(session.activityEndTime()).isEqualTo(t0.plusSeconds(3600));
    assertThat(session.durationSeconds()).isEqualTo(1200L);

    VehicleSchedule sched = new VehicleSchedule(cab, locA, 50_000L, t0, List.of(assign, session));
    assertThat(sched.vehicleId()).isEqualTo(cab.id());
    assertThat(sched.activities()).hasSize(2);

    UnassignedTrip unassigned =
        UnassignedTrip.of(trip, UnassignedReasonCode.NO_CAB_FREE_IN_TIME_WINDOW, "No cab");
    assertThat(unassigned.trip().id()).isEqualTo(trip.id());
    assertThat(unassigned.reasonCode()).isEqualTo(UnassignedReasonCode.NO_CAB_FREE_IN_TIME_WINDOW);

    FleetPlan plan =
        new FleetPlan(
            "P-1", "T-1", t0, t0.plusSeconds(7200), List.of(sched), List.of(unassigned), 1L, "h");
    assertThat(plan.planId()).isEqualTo("P-1");
    assertThat(plan.tenantId()).isEqualTo("T-1");
    assertThat(plan.parsedTenantId()).isEqualTo(TenantId.of("T-1"));
    assertThat(plan.totalAssignedTrips()).isEqualTo(1);
    assertThat(plan.totalUnassignedTrips()).isEqualTo(1);
    assertThat(plan.totalChargingSessions()).isEqualTo(1);
    assertThat(plan.vehicleSchedules()).hasSize(1);
    assertThat(plan.unassignedTrips()).hasSize(1);

    assertThat(sched.isEmpty()).isFalse();
    assertThat(sched.activityCount()).isEqualTo(2);
    assertThat(sched.initialLocation()).isEqualTo(locA);
    assertThat(sched.initialSocWh()).isEqualTo(50_000L);
    assertThat(sched.availableFrom()).isEqualTo(t0);

    ConstantSpeedDistanceProvider csp = new ConstantSpeedDistanceProvider(4000, 10);
    assertThat(csp.getDistanceAndDuration(locA, locA).distanceMeters()).isZero();
    assertThat(csp.getDistanceAndDuration(locA, locB).distanceMeters()).isEqualTo(4000);
    assertThatThrownBy(() -> new ConstantSpeedDistanceProvider(-1, 10))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ConstantSpeedDistanceProvider(4000, 0))
        .isInstanceOf(IllegalArgumentException.class);

    DistanceDuration dd = DistanceDuration.of(1000, 120);
    assertThat(dd.distanceMeters()).isEqualTo(1000);
    assertThat(dd.durationSeconds()).isEqualTo(120);
  }
}
