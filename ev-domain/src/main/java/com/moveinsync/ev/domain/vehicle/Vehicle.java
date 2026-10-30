package com.moveinsync.ev.domain.vehicle;

import com.moveinsync.ev.domain.tenant.TenantId;
import java.util.Objects;

/**
 * Immutable vehicle domain model representing an electric vehicle cab and its physical
 * characteristics.
 *
 * <p>All electrical and physical quantities are stored in integer units:
 *
 * <ul>
 *   <li>Battery capacity in Watt-hours (Wh)
 *   <li>Base consumption in Watt-hours per kilometre (Wh/km)
 *   <li>Maximum charge acceptance rate in kilowatts (kW)
 * </ul>
 *
 * @param tenantId tenant identifier
 * @param id unique vehicle identifier
 * @param batteryCapacityWh total usable battery capacity in Watt-hours
 * @param baseConsumptionWhPerKm nominal energy consumption in Watt-hours per kilometre
 * @param maxChargeKw maximum fast-charging acceptance power in kilowatts
 */
public record Vehicle(
    TenantId tenantId,
    VehicleId id,
    long batteryCapacityWh,
    long baseConsumptionWhPerKm,
    int maxChargeKw) {

  public static final int STANDARD_FLOOR_PERCENT = 15;
  public static final int DEFAULT_ROBUST_MARGIN_PERCENT = 18;

  public Vehicle {
    Objects.requireNonNull(tenantId, "tenantId must not be null");
    Objects.requireNonNull(id, "Vehicle id must not be null");
    if (batteryCapacityWh <= 0) {
      throw new IllegalArgumentException(
          "Battery capacity must be positive: " + batteryCapacityWh + " Wh");
    }
    if (baseConsumptionWhPerKm <= 0) {
      throw new IllegalArgumentException(
          "Base consumption must be positive: " + baseConsumptionWhPerKm + " Wh/km");
    }
    if (maxChargeKw <= 0) {
      throw new IllegalArgumentException(
          "Maximum charge power must be positive: " + maxChargeKw + " kW");
    }
  }

  public Vehicle(
      VehicleId id, long batteryCapacityWh, long baseConsumptionWhPerKm, int maxChargeKw) {
    this(TenantId.of("default"), id, batteryCapacityWh, baseConsumptionWhPerKm, maxChargeKw);
  }

  /**
   * Calculates the minimum required battery energy in Watt-hours for a given floor percentage.
   *
   * <p>Rounding is conservative: rounds UP so the resulting battery energy strictly satisfies or
   * exceeds the percentage constraint.
   *
   * @param floorPercentage integer percentage between 1 and 100
   * @return minimum required battery energy in Wh
   */
  public long calculateFloorSocWh(int floorPercentage) {
    if (floorPercentage <= 0 || floorPercentage > 100) {
      throw new IllegalArgumentException(
          "Floor percentage must be between 1 and 100: " + floorPercentage);
    }
    long product = Math.multiplyExact(batteryCapacityWh, (long) floorPercentage);
    return (product + 99L) / 100L;
  }

  /**
   * Calculates the hard safety floor energy in Watt-hours (15% SoC).
   *
   * @return standard safety floor in Wh (conservative rounded UP)
   */
  public long calculateStandardFloorWh() {
    return calculateFloorSocWh(STANDARD_FLOOR_PERCENT);
  }

  /**
   * Calculates the robust planning floor energy in Watt-hours (e.g. 18% SoC).
   *
   * @return robust planning floor in Wh (conservative rounded UP)
   */
  public long calculateRobustFloorWh() {
    return calculateFloorSocWh(DEFAULT_ROBUST_MARGIN_PERCENT);
  }
}
