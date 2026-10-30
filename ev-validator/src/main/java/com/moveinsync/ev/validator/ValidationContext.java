package com.moveinsync.ev.validator;

import com.moveinsync.ev.domain.charging.ChargingCurve;
import com.moveinsync.ev.domain.consumption.ConsumptionModel;
import com.moveinsync.ev.domain.distance.DistanceProvider;
import com.moveinsync.ev.domain.hub.Charger;
import com.moveinsync.ev.domain.hub.ChargerId;
import com.moveinsync.ev.domain.hub.Hub;
import com.moveinsync.ev.domain.hub.HubId;
import com.moveinsync.ev.domain.vehicle.Vehicle;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable validation context holding fleet infrastructure, models, and threshold configuration.
 *
 * <p>The validator always enforces the true safety floor (default 15%), whereas the planner uses an
 * internal robustness margin (e.g. 18%).
 */
public final class ValidationContext {

  private final Map<HubId, Hub> hubs;
  private final Map<ChargerId, Charger> chargers;
  private final ConsumptionModel consumptionModel;
  private final DistanceProvider distanceProvider;
  private final ChargingCurve chargingCurve;
  private final int batteryFloorPercentage;
  private final boolean enforceReserveToHub;
  private final boolean acOn;

  public ValidationContext(
      Map<HubId, Hub> hubs,
      Map<ChargerId, Charger> chargers,
      ConsumptionModel consumptionModel,
      DistanceProvider distanceProvider,
      ChargingCurve chargingCurve,
      int batteryFloorPercentage,
      boolean enforceReserveToHub,
      boolean acOn) {
    Objects.requireNonNull(hubs, "hubs map must not be null");
    Objects.requireNonNull(chargers, "chargers map must not be null");
    this.consumptionModel =
        Objects.requireNonNull(consumptionModel, "consumptionModel must not be null");
    this.distanceProvider =
        Objects.requireNonNull(distanceProvider, "distanceProvider must not be null");
    this.chargingCurve = Objects.requireNonNull(chargingCurve, "chargingCurve must not be null");

    if (batteryFloorPercentage <= 0 || batteryFloorPercentage >= 100) {
      throw new IllegalArgumentException(
          "batteryFloorPercentage must be between 1 and 99: " + batteryFloorPercentage);
    }

    this.hubs = Collections.unmodifiableMap(new HashMap<>(hubs));
    this.chargers = Collections.unmodifiableMap(new HashMap<>(chargers));
    this.batteryFloorPercentage = batteryFloorPercentage;
    this.enforceReserveToHub = enforceReserveToHub;
    this.acOn = acOn;
  }

  public static Builder builder() {
    return new Builder();
  }

  public Map<HubId, Hub> hubs() {
    return hubs;
  }

  public Optional<Hub> findHub(HubId hubId) {
    return Optional.ofNullable(hubs.get(hubId));
  }

  public Map<ChargerId, Charger> chargers() {
    return chargers;
  }

  public Optional<Charger> findCharger(ChargerId chargerId) {
    return Optional.ofNullable(chargers.get(chargerId));
  }

  public ConsumptionModel consumptionModel() {
    return consumptionModel;
  }

  public DistanceProvider distanceProvider() {
    return distanceProvider;
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

  public boolean acOn() {
    return acOn;
  }

  public static final class Builder {
    private final Map<HubId, Hub> hubs = new HashMap<>();
    private final Map<ChargerId, Charger> chargers = new HashMap<>();
    private ConsumptionModel consumptionModel;
    private DistanceProvider distanceProvider;
    private ChargingCurve chargingCurve = ChargingCurve.defaultCurve();
    private int batteryFloorPercentage = Vehicle.STANDARD_FLOOR_PERCENT;
    private boolean enforceReserveToHub = true;
    private boolean acOn = false;

    public Builder addHub(Hub hub) {
      Objects.requireNonNull(hub, "Hub must not be null");
      this.hubs.put(hub.id(), hub);
      return this;
    }

    public Builder addCharger(Charger charger) {
      Objects.requireNonNull(charger, "Charger must not be null");
      this.chargers.put(charger.id(), charger);
      return this;
    }

    public Builder consumptionModel(ConsumptionModel model) {
      this.consumptionModel = model;
      return this;
    }

    public Builder distanceProvider(DistanceProvider provider) {
      this.distanceProvider = provider;
      return this;
    }

    public Builder chargingCurve(ChargingCurve curve) {
      this.chargingCurve = curve;
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

    public Builder acOn(boolean acOn) {
      this.acOn = acOn;
      return this;
    }

    public ValidationContext build() {
      return new ValidationContext(
          hubs,
          chargers,
          consumptionModel,
          distanceProvider,
          chargingCurve,
          batteryFloorPercentage,
          enforceReserveToHub,
          acOn);
    }
  }
}
