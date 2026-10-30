package com.moveinsync.ev.domain.hub;

import com.moveinsync.ev.domain.tenant.TenantId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Immutable domain model representing a physical EV charger plug at a hub.
 *
 * @param tenantId tenant identifier
 * @param id unique charger identifier
 * @param hubId the hub where this charger is located
 * @param powerKw nominal rated delivery power in kilowatts (integer)
 * @param status operational status
 * @param outageWindows scheduled or observed outage windows for this charger
 */
public record Charger(
    TenantId tenantId,
    ChargerId id,
    HubId hubId,
    int powerKw,
    ChargerStatus status,
    List<OutageWindow> outageWindows) {

  public Charger {
    Objects.requireNonNull(tenantId, "tenantId must not be null");
    Objects.requireNonNull(id, "Charger id must not be null");
    Objects.requireNonNull(hubId, "HubId must not be null");
    Objects.requireNonNull(status, "ChargerStatus must not be null");
    Objects.requireNonNull(outageWindows, "outageWindows must not be null");
    if (powerKw <= 0) {
      throw new IllegalArgumentException("Charger power must be positive: " + powerKw + " kW");
    }
    outageWindows = Collections.unmodifiableList(new ArrayList<>(outageWindows));
  }

  public Charger(ChargerId id, HubId hubId, int powerKw, ChargerStatus status) {
    this(TenantId.of("default"), id, hubId, powerKw, status, List.of());
  }

  public static Charger of(String id, String hubId, int powerKw) {
    return new Charger(
        TenantId.of("default"),
        ChargerId.of(id),
        HubId.of(hubId),
        powerKw,
        ChargerStatus.OPERATIONAL,
        List.of());
  }

  public static Charger of(String tenantId, String id, String hubId, int powerKw) {
    return new Charger(
        TenantId.of(tenantId),
        ChargerId.of(id),
        HubId.of(hubId),
        powerKw,
        ChargerStatus.OPERATIONAL,
        List.of());
  }

  /**
   * Checks whether the charger is available throughout the specified interval {@code [from, to)}.
   *
   * @param from start instant of the session (inclusive)
   * @param to end instant of the session (exclusive)
   * @return true if available and not overlapping any outage window
   */
  public boolean isAvailableDuring(Instant from, Instant to) {
    if (!status.isAvailable()) {
      return false;
    }
    for (OutageWindow outage : outageWindows) {
      if (outage.overlaps(from, to)) {
        return false;
      }
    }
    return true;
  }

  /**
   * Calculates the effective charging power when connected to a specific vehicle.
   *
   * <p>Enforces the domain invariant: plug power = min(charger kW, vehicle max kW).
   *
   * @param vehicleMaxChargeKw maximum acceptance rate of the vehicle in kW
   * @return effective charging power in kW
   */
  public int calculateEffectivePowerKw(int vehicleMaxChargeKw) {
    if (vehicleMaxChargeKw <= 0) {
      throw new IllegalArgumentException(
          "Vehicle max charge power must be positive: " + vehicleMaxChargeKw);
    }
    return Math.min(this.powerKw, vehicleMaxChargeKw);
  }

  /**
   * Returns a copy of this charger with updated operational status.
   *
   * @param newStatus the new status
   * @return new Charger instance with the updated status
   */
  public Charger withStatus(ChargerStatus newStatus) {
    return new Charger(
        this.tenantId, this.id, this.hubId, this.powerKw, newStatus, this.outageWindows);
  }

  /**
   * Returns a copy of this charger with updated outage windows.
   *
   * @param newOutages the new outage windows
   * @return new Charger instance with the updated outage windows
   */
  public Charger withOutages(List<OutageWindow> newOutages) {
    return new Charger(this.tenantId, this.id, this.hubId, this.powerKw, this.status, newOutages);
  }
}
