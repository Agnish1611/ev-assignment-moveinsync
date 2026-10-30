package com.moveinsync.ev.sim;

import com.moveinsync.ev.planner.input.PlannerInput;
import com.moveinsync.ev.validator.ValidationInput;
import com.moveinsync.ev.validator.ValidationInputBuilder;

/** Shared ground truth for simulator proofs. The validator stays off the planner. */
final class SimSupport {

  private SimSupport() {
    throw new IllegalStateException("utility");
  }

  static ValidationInput groundTruth(PlannerInput fleet) {
    ValidationInputBuilder builder =
        new ValidationInputBuilder()
            .tenantId(fleet.tenantId())
            .horizon(fleet.horizonStart(), fleet.horizonEnd())
            .distanceProvider(fleet.distanceProvider())
            .consumptionModel(fleet.consumptionModel())
            .chargingCurve(fleet.chargingCurve())
            .batteryFloorPercentage(15)
            .enforceReserveToHub(true)
            .idlePowerW(0);
    fleet
        .cabs()
        .forEach(cab -> builder.addVehicle(cab.vehicle(), cab.startLocation(), cab.initialSocWh()));
    fleet.trips().forEach(builder::addTrip);
    fleet.hubs().forEach(builder::addHub);
    fleet.chargers().forEach(builder::addCharger);
    fleet.tariffs().forEach(schedule -> schedule.windows().forEach(builder::addUtcTariff));
    return builder.build();
  }
}
