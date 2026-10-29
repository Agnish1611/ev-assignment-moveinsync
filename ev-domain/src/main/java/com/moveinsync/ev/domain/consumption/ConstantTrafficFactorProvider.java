package com.moveinsync.ev.domain.consumption;

import java.time.Instant;

/** Traffic factor provider returning a constant traffic multiplier. */
public final class ConstantTrafficFactorProvider implements TrafficFactorProvider {

  private static final ConstantTrafficFactorProvider NOMINAL =
      new ConstantTrafficFactorProvider(BASIS_POINTS_SCALE);

  private final int factorBasisPoints;

  public ConstantTrafficFactorProvider(int factorBasisPoints) {
    if (factorBasisPoints <= 0) {
      throw new IllegalArgumentException(
          "Traffic factor basis points must be positive: " + factorBasisPoints);
    }
    this.factorBasisPoints = factorBasisPoints;
  }

  public static ConstantTrafficFactorProvider nominal() {
    return NOMINAL;
  }

  @Override
  public int getTrafficFactorBasisPoints(Instant instant) {
    return factorBasisPoints;
  }
}
