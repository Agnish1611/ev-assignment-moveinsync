package com.moveinsync.ev.validator;

import com.moveinsync.ev.domain.charging.ChargingCurve;
import com.moveinsync.ev.domain.consumption.ConsumptionModel;
import com.moveinsync.ev.domain.distance.DistanceProvider;
import com.moveinsync.ev.domain.hub.Charger;
import com.moveinsync.ev.domain.hub.ChargerId;
import com.moveinsync.ev.domain.hub.Hub;
import com.moveinsync.ev.domain.hub.HubId;
import com.moveinsync.ev.domain.location.Location;
import com.moveinsync.ev.domain.tariff.TariffWindow;
import com.moveinsync.ev.domain.tariff.UtcTariffWindow;
import com.moveinsync.ev.domain.tenant.TenantId;
import com.moveinsync.ev.domain.trip.Trip;
import com.moveinsync.ev.domain.trip.TripId;
import com.moveinsync.ev.domain.vehicle.Vehicle;
import com.moveinsync.ev.domain.vehicle.VehicleId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Fluent builder for {@link ValidationInput} enforcing strict physical invariants on ground truth.
 */
public class ValidationInputBuilder {

  private TenantId tenantId;
  private Instant horizonStart;
  private Instant horizonEnd;
  private final Map<VehicleId, Vehicle> vehicles = new HashMap<>();
  private final Map<VehicleId, Long> initialSocsWh = new HashMap<>();
  private final Map<VehicleId, Location> initialLocations = new HashMap<>();
  private final Map<TripId, Trip> trips = new HashMap<>();
  private final Map<HubId, Hub> hubs = new HashMap<>();
  private final Map<ChargerId, Charger> chargers = new HashMap<>();
  private final List<TariffWindow> tariffs = new ArrayList<>();
  private final List<UtcTariffWindow> utcTariffs = new ArrayList<>();
  private DistanceProvider distanceProvider;
  private ConsumptionModel consumptionModel;
  private ChargingCurve chargingCurve = ChargingCurve.defaultCurve();
  private int batteryFloorPercentage = 15;
  private boolean enforceReserveToHub = true;
  private int idlePowerW = 0;
  private int maxViolationsReportCap = Integer.MAX_VALUE;

  public ValidationInputBuilder tenantId(TenantId tenantId) {
    this.tenantId = Objects.requireNonNull(tenantId, "tenantId must not be null");
    return this;
  }

  public ValidationInputBuilder horizon(Instant start, Instant end) {
    this.horizonStart = Objects.requireNonNull(start, "horizon start must not be null");
    this.horizonEnd = Objects.requireNonNull(end, "horizon end must not be null");
    return this;
  }

  public ValidationInputBuilder addVehicle(
      Vehicle vehicle, Location initialLoc, long initialSocWh) {
    Objects.requireNonNull(vehicle, "vehicle must not be null");
    Objects.requireNonNull(initialLoc, "initialLoc must not be null");
    if (initialSocWh < 0 || initialSocWh > vehicle.batteryCapacityWh()) {
      throw new IllegalArgumentException(
          "initialSocWh ("
              + initialSocWh
              + ") must be within [0, "
              + vehicle.batteryCapacityWh()
              + "] for vehicle "
              + vehicle.id());
    }
    this.vehicles.put(vehicle.id(), vehicle);
    this.initialLocations.put(vehicle.id(), initialLoc);
    this.initialSocsWh.put(vehicle.id(), initialSocWh);
    return this;
  }

  public ValidationInputBuilder addTrip(Trip trip) {
    Objects.requireNonNull(trip, "trip must not be null");
    this.trips.put(trip.id(), trip);
    return this;
  }

  public ValidationInputBuilder addHub(Hub hub) {
    Objects.requireNonNull(hub, "hub must not be null");
    this.hubs.put(hub.id(), hub);
    return this;
  }

  public ValidationInputBuilder addCharger(Charger charger) {
    Objects.requireNonNull(charger, "charger must not be null");
    this.chargers.put(charger.id(), charger);
    return this;
  }

  public ValidationInputBuilder addTariff(TariffWindow tariff) {
    Objects.requireNonNull(tariff, "tariff must not be null");
    this.tariffs.add(tariff);
    return this;
  }

  public ValidationInputBuilder addUtcTariff(UtcTariffWindow utcTariff) {
    Objects.requireNonNull(utcTariff, "utcTariff must not be null");
    this.utcTariffs.add(utcTariff);
    return this;
  }

  public ValidationInputBuilder distanceProvider(DistanceProvider distanceProvider) {
    this.distanceProvider =
        Objects.requireNonNull(distanceProvider, "distanceProvider must not be null");
    return this;
  }

  public ValidationInputBuilder consumptionModel(ConsumptionModel consumptionModel) {
    this.consumptionModel =
        Objects.requireNonNull(consumptionModel, "consumptionModel must not be null");
    return this;
  }

  public ValidationInputBuilder chargingCurve(ChargingCurve chargingCurve) {
    this.chargingCurve = Objects.requireNonNull(chargingCurve, "chargingCurve must not be null");
    return this;
  }

  public ValidationInputBuilder batteryFloorPercentage(int percentage) {
    if (percentage <= 0 || percentage >= 100) {
      throw new IllegalArgumentException(
          "batteryFloorPercentage must be between 1 and 99: " + percentage);
    }
    this.batteryFloorPercentage = percentage;
    return this;
  }

  public ValidationInputBuilder enforceReserveToHub(boolean enforce) {
    this.enforceReserveToHub = enforce;
    return this;
  }

  public ValidationInputBuilder idlePowerW(int idlePowerW) {
    if (idlePowerW < 0) {
      throw new IllegalArgumentException("idlePowerW must be non-negative: " + idlePowerW);
    }
    this.idlePowerW = idlePowerW;
    return this;
  }

  public ValidationInputBuilder maxViolationsReportCap(int maxViolationsReportCap) {
    this.maxViolationsReportCap = maxViolationsReportCap;
    return this;
  }

  public ValidationInput build() {
    if (tenantId == null) {
      throw new IllegalArgumentException("tenantId must be set");
    }
    if (horizonStart == null) {
      horizonStart = Instant.EPOCH;
    }
    if (horizonEnd == null) {
      horizonEnd = horizonStart.plusSeconds(86400);
    }
    if (!horizonStart.isBefore(horizonEnd)) {
      throw new IllegalArgumentException(
          "horizonStart ("
              + horizonStart
              + ") must be strictly before horizonEnd ("
              + horizonEnd
              + ")");
    }

    validateChargerHubReferences();
    validateUtcTariffNonOverlap();

    return new ValidationInput(
        tenantId,
        horizonStart,
        horizonEnd,
        vehicles,
        initialSocsWh,
        initialLocations,
        trips,
        hubs,
        chargers,
        tariffs,
        utcTariffs,
        distanceProvider,
        consumptionModel,
        chargingCurve,
        batteryFloorPercentage,
        enforceReserveToHub,
        idlePowerW,
        maxViolationsReportCap);
  }

  private void validateChargerHubReferences() {
    for (Charger charger : chargers.values()) {
      if (!hubs.containsKey(charger.hubId())) {
        throw new IllegalArgumentException(
            "Charger " + charger.id() + " references unregistered hub " + charger.hubId());
      }
    }
  }

  private void validateUtcTariffNonOverlap() {
    Map<HubId, List<UtcTariffWindow>> tariffsByHub = new HashMap<>();
    for (UtcTariffWindow tw : utcTariffs) {
      tariffsByHub.computeIfAbsent(tw.hubId(), k -> new ArrayList<>()).add(tw);
    }
    for (Map.Entry<HubId, List<UtcTariffWindow>> entry : tariffsByHub.entrySet()) {
      List<UtcTariffWindow> hubWindows = new ArrayList<>(entry.getValue());
      hubWindows.sort(Comparator.comparing(UtcTariffWindow::startUtc));
      for (int i = 0; i < hubWindows.size() - 1; i++) {
        UtcTariffWindow w1 = hubWindows.get(i);
        UtcTariffWindow w2 = hubWindows.get(i + 1);
        if (w1.endUtc().isAfter(w2.startUtc())) {
          throw new IllegalArgumentException(
              "Overlapping UTC tariff windows for hub "
                  + entry.getKey()
                  + ": "
                  + w1
                  + " and "
                  + w2);
        }
      }
    }
  }
}
