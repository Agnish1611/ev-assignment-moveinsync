package com.moveinsync.ev.service.fleet;

import com.moveinsync.ev.domain.hub.Charger;
import com.moveinsync.ev.domain.hub.ChargerStatus;
import com.moveinsync.ev.domain.hub.Hub;
import com.moveinsync.ev.domain.hub.HubId;
import com.moveinsync.ev.domain.hub.OutageWindow;
import com.moveinsync.ev.domain.location.Location;
import com.moveinsync.ev.domain.tariff.TariffSchedule;
import com.moveinsync.ev.domain.tariff.TariffWindow;
import com.moveinsync.ev.domain.tariff.UtcTariffWindow;
import com.moveinsync.ev.domain.tenant.TenantId;
import com.moveinsync.ev.domain.trip.Trip;
import com.moveinsync.ev.domain.trip.TripId;
import com.moveinsync.ev.domain.vehicle.Vehicle;
import com.moveinsync.ev.domain.vehicle.VehicleId;
import com.moveinsync.ev.planner.input.CabShift;
import com.moveinsync.ev.service.fleet.FleetRows.CabRow;
import com.moveinsync.ev.service.fleet.FleetRows.ChargerRow;
import com.moveinsync.ev.service.fleet.FleetRows.HubRow;
import com.moveinsync.ev.service.fleet.FleetRows.OutageRow;
import com.moveinsync.ev.service.fleet.FleetRows.PlaceRow;
import com.moveinsync.ev.service.fleet.FleetRows.TariffRow;
import com.moveinsync.ev.service.fleet.FleetRows.TripRow;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Turns snapshot rows into domain values. Callers do not share the assembled inputs. */
final class FleetParts {

  private final TenantId tenant;
  private final Map<String, PlaceRow> places;

  FleetParts(String tenant, List<PlaceRow> places) {
    this.tenant = TenantId.of(tenant);
    this.places = new HashMap<>();
    for (PlaceRow place : places) {
      this.places.put(place.id(), place);
    }
  }

  Location place(String id) {
    PlaceRow row = places.get(id);
    if (row == null || id == null) {
      throw new FleetRejected("MISSING_LOCATION", id == null ? "" : id);
    }
    if (row.latitudeMicros() == null || row.longitudeMicros() == null) {
      throw new FleetRejected("MISSING_COORDINATES", id);
    }
    return new Location(rowId(id), row.name(), row.latitudeMicros(), row.longitudeMicros());
  }

  Hub hub(HubRow row) {
    return Hub.of(
        tenant.value(),
        row.id(),
        row.name(),
        place(row.locationId()),
        ZoneId.of(row.zoneId()),
        row.chargerCount());
  }

  Charger charger(ChargerRow row, List<OutageRow> outages) {
    return new Charger(
        tenant,
        com.moveinsync.ev.domain.hub.ChargerId.of(row.id()),
        HubId.of(row.hubId()),
        row.powerKw(),
        status(row),
        windows(row.id(), outages));
  }

  CabShift cab(CabRow row) {
    long soc = soc(row);
    return new CabShift(vehicle(row), position(row), soc, available(row));
  }

  Trip trip(TripRow row) {
    if (row.durationSeconds() == null) {
      throw new FleetRejected("MISSING_DURATION", row.id());
    }
    return new Trip(
        tenant,
        TripId.of(row.id()),
        row.start(),
        place(row.fromLocationId()),
        place(row.toLocationId()),
        row.distanceMetres(),
        row.durationSeconds(),
        row.latestFinish(),
        row.employeeRef());
  }

  List<TariffSchedule> tariffs(List<Hub> hubs, List<TariffRow> rows, Instant horizonStart) {
    Map<String, ZoneId> zones = new HashMap<>();
    for (Hub hub : hubs) {
      zones.put(hub.id().value(), hub.timeZone());
    }
    Map<String, List<UtcTariffWindow>> grouped = new HashMap<>();
    for (TariffRow row : rows) {
      ZoneId zone = zones.get(row.hubId());
      if (zone == null) {
        throw new FleetRejected("MISSING_TARIFF_HUB", row.hubId());
      }
      LocalDate day = LocalDate.ofInstant(horizonStart, zone);
      TariffWindow window =
          new TariffWindow(
              HubId.of(row.hubId()),
              LocalTime.parse(row.localStart()),
              LocalTime.parse(row.localEnd()),
              row.rateMicros());
      grouped
          .computeIfAbsent(row.hubId(), ignored -> new ArrayList<>())
          .add(window.toUtcWindow(day, zone));
    }
    List<TariffSchedule> schedules = new ArrayList<>();
    for (Map.Entry<String, List<UtcTariffWindow>> entry : grouped.entrySet()) {
      schedules.add(new TariffSchedule(HubId.of(entry.getKey()), entry.getValue()));
    }
    return schedules;
  }

  private Vehicle vehicle(CabRow row) {
    return new Vehicle(
        tenant,
        VehicleId.of(row.id()),
        row.batteryWh(),
        row.consumptionWhPerKm(),
        row.maxChargeKw());
  }

  private long soc(CabRow row) {
    if (row.stateSocWh() != null) {
      return row.stateSocWh();
    }
    if (row.shiftStartSocWh() == null) {
      throw new FleetRejected("MISSING_SHIFT_SOC", row.id());
    }
    return row.shiftStartSocWh();
  }

  private Location position(CabRow row) {
    if (row.stateSocWh() == null) {
      return place(row.locationId());
    }
    if (row.stateLatitudeMicros() == null || row.stateLongitudeMicros() == null) {
      throw new FleetRejected("MISSING_COORDINATES", row.id());
    }
    String id = row.stateLocationId() == null ? row.locationId() : row.stateLocationId();
    return new Location(rowId(id), id, row.stateLatitudeMicros(), row.stateLongitudeMicros());
  }

  private Instant available(CabRow row) {
    if (row.availableFrom() == null) {
      throw new FleetRejected("MISSING_AVAILABLE_FROM", row.id());
    }
    return row.availableFrom();
  }

  private static ChargerStatus status(ChargerRow row) {
    try {
      return ChargerStatus.valueOf(row.status());
    } catch (IllegalArgumentException ex) {
      throw new FleetRejected("CHARGER_STATUS", row.id());
    }
  }

  private static List<OutageWindow> windows(String chargerId, List<OutageRow> outages) {
    List<OutageWindow> found = new ArrayList<>();
    for (OutageRow outage : outages) {
      if (chargerId.equals(outage.chargerId())) {
        found.add(new OutageWindow(outage.start(), outage.end(), outage.sourceKey()));
      }
    }
    return found;
  }

  private static com.moveinsync.ev.domain.location.LocationId rowId(String id) {
    return com.moveinsync.ev.domain.location.LocationId.of(id);
  }
}
