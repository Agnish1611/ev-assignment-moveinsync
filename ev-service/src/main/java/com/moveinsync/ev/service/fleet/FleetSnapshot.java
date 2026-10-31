package com.moveinsync.ev.service.fleet;

import com.moveinsync.ev.service.fleet.FleetRows.CabRow;
import com.moveinsync.ev.service.fleet.FleetRows.ChargerRow;
import com.moveinsync.ev.service.fleet.FleetRows.HubRow;
import com.moveinsync.ev.service.fleet.FleetRows.OutageRow;
import com.moveinsync.ev.service.fleet.FleetRows.PlaceRow;
import com.moveinsync.ev.service.fleet.FleetRows.ShardRow;
import com.moveinsync.ev.service.fleet.FleetRows.TariffRow;
import com.moveinsync.ev.service.fleet.FleetRows.TripRow;
import java.util.List;

/** One shard's catalog, live state, and outages. Lists are copied. */
public record FleetSnapshot(
    String tenant,
    ShardRow shard,
    List<PlaceRow> places,
    List<HubRow> hubs,
    List<ChargerRow> chargers,
    List<OutageRow> outages,
    List<CabRow> cabs,
    List<TripRow> trips,
    List<TariffRow> tariffs) {

  public FleetSnapshot {
    places = List.copyOf(places);
    hubs = List.copyOf(hubs);
    chargers = List.copyOf(chargers);
    outages = List.copyOf(outages);
    cabs = List.copyOf(cabs);
    trips = List.copyOf(trips);
    tariffs = List.copyOf(tariffs);
  }
}
