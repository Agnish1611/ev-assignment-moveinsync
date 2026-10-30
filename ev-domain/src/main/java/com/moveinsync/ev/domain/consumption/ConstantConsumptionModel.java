package com.moveinsync.ev.domain.consumption;

import com.moveinsync.ev.domain.vehicle.Vehicle;
import java.time.Instant;
import java.util.Objects;

/** Baseline consumption model with fixed traffic factor and optional AC load. */
public final class ConstantConsumptionModel implements ConsumptionModel {

  private final int trafficFactorBasisPoints;
  private final int acOnFactorBasisPoints;

  public ConstantConsumptionModel() {
    this(FACTOR_SCALE_BASIS_POINTS, StandardConsumptionModel.DEFAULT_AC_FACTOR_BASIS_POINTS);
  }

  public ConstantConsumptionModel(int trafficFactorBasisPoints, int acOnFactorBasisPoints) {
    if (trafficFactorBasisPoints <= 0) {
      throw new IllegalArgumentException(
          "trafficFactorBasisPoints must be positive: " + trafficFactorBasisPoints);
    }
    if (acOnFactorBasisPoints <= 0) {
      throw new IllegalArgumentException(
          "acOnFactorBasisPoints must be positive: " + acOnFactorBasisPoints);
    }
    this.trafficFactorBasisPoints = trafficFactorBasisPoints;
    this.acOnFactorBasisPoints = acOnFactorBasisPoints;
  }

  public int trafficFactorBasisPoints() {
    return trafficFactorBasisPoints;
  }

  public int acOnFactorBasisPoints() {
    return acOnFactorBasisPoints;
  }

  @Override
  public long calculateEnergyWh(
      Vehicle vehicle, int distanceMeters, Instant travelTime, boolean acOn) {
    Objects.requireNonNull(vehicle, "Vehicle must not be null");
    Objects.requireNonNull(travelTime, "TravelTime must not be null");

    if (distanceMeters <= 0) {
      return 0L;
    }

    long baseProduct = Math.multiplyExact(vehicle.baseConsumptionWhPerKm(), (long) distanceMeters);
    long baseWh = (baseProduct + 999L) / 1000L;

    int acBp = acOn ? acOnFactorBasisPoints : FACTOR_SCALE_BASIS_POINTS;

    long numerator = Math.multiplyExact(baseWh, (long) trafficFactorBasisPoints);
    numerator = Math.multiplyExact(numerator, (long) acBp);

    long denominator = (long) FACTOR_SCALE_BASIS_POINTS * (long) FACTOR_SCALE_BASIS_POINTS;

    return (numerator + denominator - 1L) / denominator;
  }
}
