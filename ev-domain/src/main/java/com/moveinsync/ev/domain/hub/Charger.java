package com.moveinsync.ev.domain.hub;

import java.util.Objects;

/**
 * Immutable domain model representing a physical EV charger plug at a hub.
 *
 * @param id unique charger identifier
 * @param hubId the hub where this charger is located
 * @param powerKw nominal rated delivery power in kilowatts (integer)
 * @param status operational status
 */
public record Charger(ChargerId id, HubId hubId, int powerKw, ChargerStatus status) {

  public Charger {
    Objects.requireNonNull(id, "Charger id must not be null");
    Objects.requireNonNull(hubId, "HubId must not be null");
    Objects.requireNonNull(status, "ChargerStatus must not be null");
    if (powerKw <= 0) {
      throw new IllegalArgumentException("Charger power must be positive: " + powerKw + " kW");
    }
  }

  public static Charger of(String id, String hubId, int powerKw) {
    return new Charger(ChargerId.of(id), HubId.of(hubId), powerKw, ChargerStatus.OPERATIONAL);
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
    return new Charger(this.id, this.hubId, this.powerKw, newStatus);
  }
}
