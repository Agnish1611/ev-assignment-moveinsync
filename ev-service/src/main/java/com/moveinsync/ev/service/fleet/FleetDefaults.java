package com.moveinsync.ev.service.fleet;

import com.moveinsync.ev.domain.charging.ChargingCurve;
import com.moveinsync.ev.domain.consumption.ConstantConsumptionModel;
import com.moveinsync.ev.domain.consumption.ConsumptionModel;
import com.moveinsync.ev.domain.distance.ConstantSpeedDistanceProvider;
import com.moveinsync.ev.domain.distance.DistanceProvider;

/**
 * Physics attached to a loaded fleet. These match the published evening snapshot until the
 * haversine provider replaces the constant-speed default.
 */
public final class FleetDefaults {

  public static final int DEADHEAD_METRES = 5_000;
  public static final int SPEED_METRES_PER_SECOND = 10;
  public static final int BATTERY_FLOOR_PERCENT = 15;

  private FleetDefaults() {
    throw new IllegalStateException("utility");
  }

  public static DistanceProvider distance() {
    return new ConstantSpeedDistanceProvider(DEADHEAD_METRES, SPEED_METRES_PER_SECOND);
  }

  public static ConsumptionModel consumption() {
    return new ConstantConsumptionModel();
  }

  public static ChargingCurve curve() {
    return ChargingCurve.defaultCurve();
  }
}
