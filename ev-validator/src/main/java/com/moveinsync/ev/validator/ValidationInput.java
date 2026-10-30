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
 * Immutable ground-truth input snapshot for independent plan validation.
 *
 * <p>In accordance with Decision D1, {@link ValidationInput} represents the authoritative ground
 * truth against which candidate plans are verified. The validator never trusts physical vehicle
 * capacities, trip driving distances, charger powers, or tariff windows declared inside the
 * candidate plan.
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

  public ValidationInput(
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
    this.tenantId = Objects.requireNonNull(tenantId, "tenantId must not be null");
    this.horizonStart = Objects.requireNonNull(horizonStart, "horizonStart must not be null");
    this.horizonEnd = Objects.requireNonNull(horizonEnd, "horizonEnd must not be null");
    if (!horizonStart.isBefore(horizonEnd)) {
      throw new IllegalArgumentException(
          "horizonStart (" + horizonStart + ") must be before horizonEnd (" + horizonEnd + ")");
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

    if (batteryFloorPercentage <= 0 || batteryFloorPercentage >= 100) {
      throw new IllegalArgumentException(
          "batteryFloorPercentage must be between 1 and 99: " + batteryFloorPercentage);
    }
    this.batteryFloorPercentage = batteryFloorPercentage;
    this.enforceReserveToHub = enforceReserveToHub;
    this.idlePowerW = idlePowerW;
  }

  public ValidationInput(
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
        List.of(),
        distanceProvider,
        consumptionModel,
        chargingCurve,
        batteryFloorPercentage,
        enforceReserveToHub,
        idlePowerW);
  }

  public static Builder builder() {
    return new Builder();
  }

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

  /**
   * Converts this validation input into a {@link ValidationContext} for sub-validator delegate
   * engines.
   */
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

  public static final class Builder {
    private TenantId tenantId = TenantId.of("default");
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

    public Builder tenantId(TenantId tenantId) {
      this.tenantId = Objects.requireNonNull(tenantId);
      return this;
    }

    public Builder horizon(Instant start, Instant end) {
      this.horizonStart = Objects.requireNonNull(start);
      this.horizonEnd = Objects.requireNonNull(end);
      return this;
    }

    public Builder addVehicle(Vehicle vehicle, Location initialLoc, long initialSocWh) {
      Objects.requireNonNull(vehicle, "vehicle must not be null");
      this.vehicles.put(vehicle.id(), vehicle);
      this.initialLocations.put(vehicle.id(), Objects.requireNonNull(initialLoc));
      this.initialSocsWh.put(vehicle.id(), initialSocWh);
      return this;
    }

    public Builder addTrip(Trip trip) {
      Objects.requireNonNull(trip, "trip must not be null");
      this.trips.put(trip.id(), trip);
      return this;
    }

    public Builder addHub(Hub hub) {
      Objects.requireNonNull(hub, "hub must not be null");
      this.hubs.put(hub.id(), hub);
      return this;
    }

    public Builder addCharger(Charger charger) {
      Objects.requireNonNull(charger, "charger must not be null");
      this.chargers.put(charger.id(), charger);
      return this;
    }

    public Builder addTariff(TariffWindow tariff) {
      Objects.requireNonNull(tariff, "tariff must not be null");
      this.tariffs.add(tariff);
      return this;
    }

    public Builder addUtcTariff(UtcTariffWindow utcTariff) {
      Objects.requireNonNull(utcTariff, "utcTariff must not be null");
      this.utcTariffs.add(utcTariff);
      return this;
    }

    public Builder distanceProvider(DistanceProvider distanceProvider) {
      this.distanceProvider = Objects.requireNonNull(distanceProvider);
      return this;
    }

    public Builder consumptionModel(ConsumptionModel consumptionModel) {
      this.consumptionModel = Objects.requireNonNull(consumptionModel);
      return this;
    }

    public Builder chargingCurve(ChargingCurve chargingCurve) {
      this.chargingCurve = Objects.requireNonNull(chargingCurve);
      return this;
    }

    public Builder batteryFloorPercentage(int percentage) {
      this.batteryFloorPercentage = percentage;
      return this;
    }

    public Builder enforceReserveToHub(boolean enforce) {
      this.enforceReserveToHub = enforce;
      return this;
    }

    public Builder idlePowerW(int idlePowerW) {
      this.idlePowerW = idlePowerW;
      return this;
    }

    public ValidationInput build() {
      if (horizonStart == null) {
        horizonStart = Instant.EPOCH;
      }
      if (horizonEnd == null) {
        horizonEnd = horizonStart.plusSeconds(86400);
      }
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
          idlePowerW);
    }
  }
}
