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
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Authoritative ground truth snapshot against which candidate fleet plans are verified.
 *
 * <p>Enforces the independent trust model (Decision D1). Ground truth values are immutable and
 * strictly validated at construction.
 */
public final class ValidationInput {

  private final TenantId tenantId;
  private final Instant horizonStart;
  private final Instant horizonEnd;
  private final Map<VehicleId, Vehicle> vehicles;
  private final Map<VehicleId, Long> initialSocsWh;
  private final Map<VehicleId, Location> initialLocations;
  private final Map<TripId, Trip> trips;
  private final Map<HubId, Hub> hubs;
  private final Map<ChargerId, Charger> chargers;
  private final List<TariffWindow> tariffs;
  private final List<UtcTariffWindow> utcTariffs;
  private final DistanceProvider distanceProvider;
  private final ConsumptionModel consumptionModel;
  private final ChargingCurve chargingCurve;
  private final int batteryFloorPercentage;
  private final boolean enforceReserveToHub;
  private final int idlePowerW;
  private final int maxViolationsReportCap;

  ValidationInput(
      TenantId tenantId,
      Instant horizonStart,
      Instant horizonEnd,
      Map<VehicleId, Vehicle> vehicles,
      Map<VehicleId, Long> initialSocsWh,
      Map<VehicleId, Location> initialLocations,
      Map<TripId, Trip> trips,
      Map<HubId, Hub> hubs,
      Map<ChargerId, Charger> chargers,
      List<TariffWindow> tariffs,
      List<UtcTariffWindow> utcTariffs,
      DistanceProvider distanceProvider,
      ConsumptionModel consumptionModel,
      ChargingCurve chargingCurve,
      int batteryFloorPercentage,
      boolean enforceReserveToHub,
      int idlePowerW,
      int maxViolationsReportCap) {
    this.tenantId = Objects.requireNonNull(tenantId, "tenantId must not be null");
    this.horizonStart = Objects.requireNonNull(horizonStart, "horizonStart must not be null");
    this.horizonEnd = Objects.requireNonNull(horizonEnd, "horizonEnd must not be null");
    if (!horizonStart.isBefore(horizonEnd)) {
      throw new IllegalArgumentException(
          "horizonStart ("
              + horizonStart
              + ") must be strictly before horizonEnd ("
              + horizonEnd
              + ")");
    }
    if (batteryFloorPercentage <= 0 || batteryFloorPercentage >= 100) {
      throw new IllegalArgumentException(
          "batteryFloorPercentage must be between 1 and 99: " + batteryFloorPercentage);
    }
    if (idlePowerW < 0) {
      throw new IllegalArgumentException("idlePowerW must not be negative: " + idlePowerW);
    }
    this.vehicles = Collections.unmodifiableMap(new HashMap<>(vehicles));
    this.initialSocsWh = Collections.unmodifiableMap(new HashMap<>(initialSocsWh));
    this.initialLocations = Collections.unmodifiableMap(new HashMap<>(initialLocations));
    this.trips = Collections.unmodifiableMap(new HashMap<>(trips));
    this.hubs = Collections.unmodifiableMap(new HashMap<>(hubs));
    this.chargers = Collections.unmodifiableMap(new HashMap<>(chargers));
    this.tariffs = Collections.unmodifiableList(new ArrayList<>(tariffs));
    this.utcTariffs =
        utcTariffs != null ? Collections.unmodifiableList(new ArrayList<>(utcTariffs)) : List.of();
    this.distanceProvider =
        Objects.requireNonNull(distanceProvider, "distanceProvider must not be null");
    this.consumptionModel =
        Objects.requireNonNull(consumptionModel, "consumptionModel must not be null");
    this.chargingCurve = Objects.requireNonNull(chargingCurve, "chargingCurve must not be null");
    this.batteryFloorPercentage = batteryFloorPercentage;
    this.enforceReserveToHub = enforceReserveToHub;
    this.idlePowerW = idlePowerW;
    this.maxViolationsReportCap = maxViolationsReportCap;
  }

  ValidationInput(
      TenantId tenantId,
      Instant horizonStart,
      Instant horizonEnd,
      Map<VehicleId, Vehicle> vehicles,
      Map<VehicleId, Long> initialSocsWh,
      Map<VehicleId, Location> initialLocations,
      Map<TripId, Trip> trips,
      Map<HubId, Hub> hubs,
      Map<ChargerId, Charger> chargers,
      List<TariffWindow> tariffs,
      List<UtcTariffWindow> utcTariffs,
      DistanceProvider distanceProvider,
      ConsumptionModel consumptionModel,
      ChargingCurve chargingCurve,
      int batteryFloorPercentage,
      boolean enforceReserveToHub,
      int idlePowerW) {
    this(
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
        Integer.MAX_VALUE);
  }

  public static ValidationInputBuilder builder() {
    return new ValidationInputBuilder();
  }

  public static final class Builder extends ValidationInputBuilder {}

  public TenantId tenantId() {
    return tenantId;
  }

  public Instant horizonStart() {
    return horizonStart;
  }

  public Instant horizonEnd() {
    return horizonEnd;
  }

  public Map<VehicleId, Vehicle> vehicles() {
    return vehicles;
  }

  public Optional<Vehicle> findVehicle(VehicleId id) {
    return Optional.ofNullable(vehicles.get(id));
  }

  public Map<VehicleId, Long> initialSocsWh() {
    return initialSocsWh;
  }

  public Map<VehicleId, Location> initialLocations() {
    return initialLocations;
  }

  public Map<TripId, Trip> trips() {
    return trips;
  }

  public Optional<Trip> findTrip(TripId id) {
    return Optional.ofNullable(trips.get(id));
  }

  public Map<HubId, Hub> hubs() {
    return hubs;
  }

  public Optional<Hub> findHub(HubId id) {
    return Optional.ofNullable(hubs.get(id));
  }

  public Map<ChargerId, Charger> chargers() {
    return chargers;
  }

  public Optional<Charger> findCharger(ChargerId id) {
    return Optional.ofNullable(chargers.get(id));
  }

  public List<TariffWindow> tariffs() {
    return tariffs;
  }

  public List<UtcTariffWindow> utcTariffs() {
    return utcTariffs;
  }

  public DistanceProvider distanceProvider() {
    return distanceProvider;
  }

  public ConsumptionModel consumptionModel() {
    return consumptionModel;
  }

  public ChargingCurve chargingCurve() {
    return chargingCurve;
  }

  public int batteryFloorPercentage() {
    return batteryFloorPercentage;
  }

  public boolean enforceReserveToHub() {
    return enforceReserveToHub;
  }

  public int idlePowerW() {
    return idlePowerW;
  }

  public int maxViolationsReportCap() {
    return maxViolationsReportCap;
  }

  public ValidationContext toValidationContext() {
    ValidationContext.Builder b =
        ValidationContext.builder()
            .consumptionModel(consumptionModel)
            .distanceProvider(distanceProvider)
            .chargingCurve(chargingCurve)
            .batteryFloorPercentage(batteryFloorPercentage)
            .enforceReserveToHub(enforceReserveToHub);
    hubs.values().forEach(b::addHub);
    chargers.values().forEach(b::addCharger);
    return b.build();
  }
}
