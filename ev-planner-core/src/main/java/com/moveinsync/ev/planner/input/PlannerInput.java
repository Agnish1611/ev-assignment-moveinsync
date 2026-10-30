package com.moveinsync.ev.planner.input;

import com.moveinsync.ev.domain.charging.ChargingCurve;
import com.moveinsync.ev.domain.consumption.ConsumptionModel;
import com.moveinsync.ev.domain.distance.DistanceProvider;
import com.moveinsync.ev.domain.hub.Charger;
import com.moveinsync.ev.domain.hub.Hub;
import com.moveinsync.ev.domain.tariff.TariffSchedule;
import com.moveinsync.ev.domain.tenant.TenantId;
import com.moveinsync.ev.domain.trip.Trip;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Ground truth for one planning run. This type is not the validator's input. The planner never
 * reads validator classes.
 *
 * <p>{@code inputHash} is the SHA-256 of the canonical snapshot. The same snapshot in a different
 * list order produces the same hash.
 */
public final class PlannerInput {

  private final TenantId tenantId;
  private final Instant horizonStart;
  private final Instant horizonEnd;
  private final List<CabShift> cabs;
  private final List<Trip> trips;
  private final List<Hub> hubs;
  private final List<Charger> chargers;
  private final List<TariffSchedule> tariffs;
  private final ConsumptionModel consumptionModel;
  private final DistanceProvider distanceProvider;
  private final ChargingCurve chargingCurve;
  private final boolean acOn;
  private final long idlePowerW;
  private final String inputHash;

  private PlannerInput(
      TenantId tenantId,
      Instant horizonStart,
      Instant horizonEnd,
      List<CabShift> cabs,
      List<Trip> trips,
      List<Hub> hubs,
      List<Charger> chargers,
      List<TariffSchedule> tariffs,
      ConsumptionModel consumptionModel,
      DistanceProvider distanceProvider,
      ChargingCurve chargingCurve,
      boolean acOn,
      long idlePowerW) {
    this.tenantId = Objects.requireNonNull(tenantId, "tenantId must not be null");
    PlannerInputChecks.requireHorizon(horizonStart, horizonEnd);
    PlannerInputChecks.requireIdle(idlePowerW);
    this.horizonStart = horizonStart;
    this.horizonEnd = horizonEnd;
    this.cabs = freeze(PlannerInputChecks.copyCabs(cabs, tenantId, horizonStart, horizonEnd));
    this.trips = freeze(PlannerInputChecks.copyTrips(trips, tenantId, horizonStart, horizonEnd));
    this.hubs = freeze(PlannerInputChecks.copyHubs(hubs, tenantId));
    this.chargers = freeze(PlannerInputChecks.copyChargers(chargers, this.hubs, tenantId));
    this.tariffs = freeze(PlannerInputChecks.copyTariffs(tariffs, this.hubs));
    this.consumptionModel =
        Objects.requireNonNull(consumptionModel, "consumptionModel must not be null");
    this.distanceProvider =
        Objects.requireNonNull(distanceProvider, "distanceProvider must not be null");
    this.chargingCurve = Objects.requireNonNull(chargingCurve, "chargingCurve must not be null");
    this.acOn = acOn;
    this.idlePowerW = idlePowerW;
    this.inputHash = InputDigest.of(this);
  }

  public static Builder builder() {
    return new Builder();
  }

  private static <T> List<T> freeze(List<T> items) {
    return Collections.unmodifiableList(new ArrayList<>(items));
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

  public List<CabShift> cabs() {
    return List.copyOf(cabs);
  }

  public List<Trip> trips() {
    return List.copyOf(trips);
  }

  public List<Hub> hubs() {
    return List.copyOf(hubs);
  }

  public List<Charger> chargers() {
    return List.copyOf(chargers);
  }

  public List<TariffSchedule> tariffs() {
    return List.copyOf(tariffs);
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

  public boolean acOn() {
    return acOn;
  }

  public long idlePowerW() {
    return idlePowerW;
  }

  public String inputHash() {
    return inputHash;
  }

  /** Collects one shift. Defaults are AC off, idle power 0 W, and the default charging curve. */
  public static final class Builder {
    private TenantId tenantId;
    private Instant horizonStart;
    private Instant horizonEnd;
    private final List<CabShift> cabs = new ArrayList<>();
    private final List<Trip> trips = new ArrayList<>();
    private final List<Hub> hubs = new ArrayList<>();
    private final List<Charger> chargers = new ArrayList<>();
    private final List<TariffSchedule> tariffs = new ArrayList<>();
    private ConsumptionModel consumptionModel;
    private DistanceProvider distanceProvider;
    private ChargingCurve chargingCurve = ChargingCurve.defaultCurve();
    private boolean acOn;
    private long idlePowerW;

    public Builder tenantId(TenantId tenantId) {
      this.tenantId = tenantId;
      return this;
    }

    public Builder horizon(Instant horizonStart, Instant horizonEnd) {
      this.horizonStart = horizonStart;
      this.horizonEnd = horizonEnd;
      return this;
    }

    public Builder addCab(CabShift cab) {
      cabs.add(Objects.requireNonNull(cab, "cab must not be null"));
      return this;
    }

    public Builder addTrip(Trip trip) {
      trips.add(Objects.requireNonNull(trip, "trip must not be null"));
      return this;
    }

    public Builder addHub(Hub hub) {
      hubs.add(Objects.requireNonNull(hub, "hub must not be null"));
      return this;
    }

    public Builder addCharger(Charger charger) {
      chargers.add(Objects.requireNonNull(charger, "charger must not be null"));
      return this;
    }

    public Builder addTariff(TariffSchedule tariff) {
      tariffs.add(Objects.requireNonNull(tariff, "tariff must not be null"));
      return this;
    }

    public Builder consumptionModel(ConsumptionModel consumptionModel) {
      this.consumptionModel = consumptionModel;
      return this;
    }

    public Builder distanceProvider(DistanceProvider distanceProvider) {
      this.distanceProvider = distanceProvider;
      return this;
    }

    public Builder chargingCurve(ChargingCurve chargingCurve) {
      this.chargingCurve = chargingCurve;
      return this;
    }

    public Builder acOn(boolean acOn) {
      this.acOn = acOn;
      return this;
    }

    public Builder idlePowerW(long idlePowerW) {
      this.idlePowerW = idlePowerW;
      return this;
    }

    public PlannerInput build() {
      return new PlannerInput(
          tenantId,
          horizonStart,
          horizonEnd,
          cabs,
          trips,
          hubs,
          chargers,
          tariffs,
          consumptionModel,
          distanceProvider,
          chargingCurve,
          acOn,
          idlePowerW);
    }
  }
}
