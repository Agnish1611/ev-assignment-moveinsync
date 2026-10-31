package com.moveinsync.ev.service.fleet;

import com.moveinsync.ev.domain.hub.Charger;
import com.moveinsync.ev.domain.hub.Hub;
import com.moveinsync.ev.domain.tenant.TenantId;
import com.moveinsync.ev.planner.input.PlannerInput;
import com.moveinsync.ev.service.fleet.FleetRows.CabRow;
import com.moveinsync.ev.service.fleet.FleetRows.ChargerRow;
import com.moveinsync.ev.service.fleet.FleetRows.HubRow;
import com.moveinsync.ev.service.fleet.FleetRows.TripRow;
import java.util.ArrayList;
import java.util.List;

/** Builds planner input from snapshot rows. It does not read the validation mapper. */
public final class PlannerFleetMapper {

  public PlannerInput map(FleetSnapshot snapshot) {
    FleetParts parts = new FleetParts(snapshot.tenant(), snapshot.places());
    List<Hub> hubs = hubs(parts, snapshot);
    List<Charger> chargers = chargers(parts, snapshot);
    PlannerInput.Builder builder =
        PlannerInput.builder()
            .tenantId(TenantId.of(snapshot.tenant()))
            .horizon(snapshot.shard().start(), snapshot.shard().end())
            .distanceProvider(FleetDefaults.distance())
            .consumptionModel(FleetDefaults.consumption())
            .chargingCurve(FleetDefaults.curve());
    hubs.forEach(builder::addHub);
    chargers.forEach(builder::addCharger);
    for (CabRow cab : snapshot.cabs()) {
      builder.addCab(parts.cab(cab));
    }
    for (TripRow trip : snapshot.trips()) {
      builder.addTrip(parts.trip(trip));
    }
    parts.tariffs(hubs, snapshot.tariffs(), snapshot.shard().start()).forEach(builder::addTariff);
    return builder.build();
  }

  private static List<Hub> hubs(FleetParts parts, FleetSnapshot snapshot) {
    List<Hub> hubs = new ArrayList<>();
    for (HubRow row : snapshot.hubs()) {
      hubs.add(parts.hub(row));
    }
    return hubs;
  }

  private static List<Charger> chargers(FleetParts parts, FleetSnapshot snapshot) {
    List<Charger> chargers = new ArrayList<>();
    for (ChargerRow row : snapshot.chargers()) {
      chargers.add(parts.charger(row, snapshot.outages()));
    }
    return chargers;
  }
}
