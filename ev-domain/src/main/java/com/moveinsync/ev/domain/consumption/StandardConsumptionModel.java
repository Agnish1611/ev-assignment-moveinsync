package com.moveinsync.ev.domain.consumption;

import com.moveinsync.ev.domain.vehicle.Vehicle;
import java.time.Instant;
import java.util.Objects;

/**
 * Standard implementation of {@link ConsumptionModel} applying nominal vehicle consumption,
 * time-varying traffic factors, and air-conditioning load.
 *
 * <p>Formula:
 *
 * <pre>
 *   BaseWh = ceil(baseConsumptionWhPerKm * distanceMeters / 1000)
 *   TotalWh = ceil(BaseWh * trafficFactorBp * acFactorBp / (10_000 * 10_000))
 * </pre>
 *
 * <p>Rounding is conservative: rounds UP at every stage so consumption is never underestimated.
 */
public final class StandardConsumptionModel implements ConsumptionModel {

  public static final int DEFAULT_AC_FACTOR_BASIS_POINTS = 11_500; // 1.15x

  private final TrafficFactorProvider trafficFactorProvider;
  private final int acOnFactorBasisPoints;

  public StandardConsumptionModel(TrafficFactorProvider trafficFactorProvider) {
    this(trafficFactorProvider, DEFAULT_AC_FACTOR_BASIS_POINTS);
  }

  public StandardConsumptionModel(
      TrafficFactorProvider trafficFactorProvider, int acOnFactorBasisPoints) {
    this.trafficFactorProvider =
        Objects.requireNonNull(trafficFactorProvider, "trafficFactorProvider must not be null");
    if (acOnFactorBasisPoints < FACTOR_SCALE_BASIS_POINTS) {
      throw new IllegalArgumentException(
          "acOnFactorBasisPoints must be >= "
              + FACTOR_SCALE_BASIS_POINTS
              + ": "
              + acOnFactorBasisPoints);
    }
    this.acOnFactorBasisPoints = acOnFactorBasisPoints;
  }

  @Override
  public long calculateEnergyWh(
      Vehicle vehicle, int distanceMeters, Instant travelTime, boolean acOn) {
    Objects.requireNonNull(vehicle, "Vehicle must not be null");
    Objects.requireNonNull(travelTime, "TravelTime must not be null");

    if (distanceMeters <= 0) {
      return 0L;
    }

    // Step 1: Base energy in Wh for distance (conservative round UP)
    long baseProduct = Math.multiplyExact(vehicle.baseConsumptionWhPerKm(), (long) distanceMeters);
    long baseWh = (baseProduct + 999L) / 1000L;

    // Step 2: Multipliers in basis points
    int trafficBp = trafficFactorProvider.getTrafficFactorBasisPoints(travelTime);
    int acBp = acOn ? acOnFactorBasisPoints : FACTOR_SCALE_BASIS_POINTS;

    // Step 3: Combined multiplication with conservative round UP
    long numerator = Math.multiplyExact(baseWh, (long) trafficBp);
    numerator = Math.multiplyExact(numerator, (long) acBp);

    long denominator = (long) FACTOR_SCALE_BASIS_POINTS * (long) FACTOR_SCALE_BASIS_POINTS;

    return (numerator + denominator - 1L) / denominator;
  }
}
