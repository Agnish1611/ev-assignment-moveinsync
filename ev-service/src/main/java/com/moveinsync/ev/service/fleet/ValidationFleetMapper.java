package com.moveinsync.ev.service.fleet;

import com.moveinsync.ev.domain.hub.Charger;
import com.moveinsync.ev.domain.hub.Hub;
import com.moveinsync.ev.domain.tariff.TariffSchedule;
import com.moveinsync.ev.domain.tariff.UtcTariffWindow;
import com.moveinsync.ev.domain.tenant.TenantId;
import com.moveinsync.ev.planner.input.CabShift;
import com.moveinsync.ev.service.fleet.FleetRows.CabRow;
import com.moveinsync.ev.service.fleet.FleetRows.ChargerRow;
import com.moveinsync.ev.service.fleet.FleetRows.HubRow;
import com.moveinsync.ev.service.fleet.FleetRows.TripRow;
import com.moveinsync.ev.validator.ValidationInput;
import com.moveinsync.ev.validator.ValidationInputBuilder;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds validator ground truth from snapshot rows. It does not read {@link PlannerFleetMapper}.
 */
public final class ValidationFleetMapper {

  public ValidationInput map(FleetSnapshot snapshot) {
    FleetParts parts = new FleetParts(snapshot.tenant(), snapshot.places());
    ValidationInputBuilder builder =
        new ValidationInputBuilder()
            .tenantId(TenantId.of(snapshot.tenant()))
            .horizon(snapshot.shard().start(), snapshot.shard().end())
            .distanceProvider(FleetDefaults.distance())
            .consumptionModel(FleetDefaults.consumption())
            .chargingCurve(FleetDefaults.curve())
            .batteryFloorPercentage(FleetDefaults.BATTERY_FLOOR_PERCENT)
            .enforceReserveToHub(true)
            .idlePowerW(0);
    List<Hub> hubs = new ArrayList<>();
    for (HubRow row : snapshot.hubs()) {
      Hub hub = parts.hub(row);
      hubs.add(hub);
      builder.addHub(hub);
    }
    for (ChargerRow row : snapshot.chargers()) {
      Charger charger = parts.charger(row, snapshot.outages());
      builder.addCharger(charger);
    }
    for (CabRow row : snapshot.cabs()) {
      CabShift cab = parts.cab(row);
      builder.addVehicle(cab.vehicle(), cab.startLocation(), cab.initialSocWh());
    }
    for (TripRow row : snapshot.trips()) {
      builder.addTrip(parts.trip(row));
    }
    for (TariffSchedule schedule :
        parts.tariffs(hubs, snapshot.tariffs(), snapshot.shard().start())) {
      for (UtcTariffWindow window : schedule.windows()) {
        builder.addUtcTariff(window);
      }
    }
    return builder.build();
  }
}
