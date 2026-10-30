package com.moveinsync.ev.planner.input;

import com.moveinsync.ev.domain.hub.Charger;
import com.moveinsync.ev.domain.hub.Hub;
import com.moveinsync.ev.domain.tariff.TariffSchedule;
import com.moveinsync.ev.domain.tenant.TenantId;
import com.moveinsync.ev.domain.trip.Trip;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Sorts planner input and rejects snapshots that are not a single tenant's shift. */
final class PlannerInputChecks {

  private PlannerInputChecks() {}

  static void requireHorizon(Instant horizonStart, Instant horizonEnd) {
    if (horizonStart == null || horizonEnd == null) {
      throw new IllegalArgumentException("horizon must not be null");
    }
    if (!horizonStart.isBefore(horizonEnd)) {
      throw new IllegalArgumentException("horizonStart must be before horizonEnd");
    }
  }

  static void requireIdle(long idlePowerW) {
    if (idlePowerW < 0) {
      throw new IllegalArgumentException("idlePowerW must be non-negative: " + idlePowerW);
    }
  }

  static List<CabShift> copyCabs(
      List<CabShift> cabs, TenantId tenantId, Instant start, Instant end) {
    List<CabShift> sorted = sorted(cabs, Comparator.comparing(cab -> cab.vehicle().id().value()));
    Set<String> ids = new HashSet<>();
    for (CabShift cab : sorted) {
      if (!ids.add(cab.vehicle().id().value())) {
        throw new IllegalArgumentException("duplicate cab id: " + cab.vehicle().id().value());
      }
      if (!cab.vehicle().tenantId().equals(tenantId)) {
        throw new IllegalArgumentException("cab tenant does not match planner tenant");
      }
      if (cab.initialSocWh() > cab.vehicle().batteryCapacityWh()) {
        throw new IllegalArgumentException("initialSocWh exceeds battery capacity");
      }
      if (cab.availableFrom().isBefore(start) || !cab.availableFrom().isBefore(end)) {
        throw new IllegalArgumentException("cab availableFrom is outside the horizon");
      }
    }
    return sorted;
  }

  static List<Trip> copyTrips(List<Trip> trips, TenantId tenantId, Instant start, Instant end) {
    List<Trip> sorted =
        sorted(trips, Comparator.comparing(Trip::startTs).thenComparing(trip -> trip.id().value()));
    Set<String> ids = new HashSet<>();
    for (Trip trip : sorted) {
      if (!ids.add(trip.id().value())) {
        throw new IllegalArgumentException("duplicate trip id: " + trip.id().value());
      }
      if (!trip.tenantId().equals(tenantId)) {
        throw new IllegalArgumentException("trip tenant does not match planner tenant");
      }
      if (trip.startTs().isBefore(start) || trip.latestFinishTs().isAfter(end)) {
        throw new IllegalArgumentException("trip is outside the horizon");
      }
    }
    return sorted;
  }

  static List<Hub> copyHubs(List<Hub> hubs, TenantId tenantId) {
    List<Hub> sorted = sorted(hubs, Comparator.comparing(hub -> hub.id().value()));
    Set<String> ids = new HashSet<>();
    for (Hub hub : sorted) {
      if (!ids.add(hub.id().value())) {
        throw new IllegalArgumentException("duplicate hub id: " + hub.id().value());
      }
      if (!hub.tenantId().equals(tenantId)) {
        throw new IllegalArgumentException("hub tenant does not match planner tenant");
      }
    }
    return sorted;
  }

  static List<Charger> copyChargers(List<Charger> chargers, List<Hub> hubs, TenantId tenantId) {
    List<Charger> sorted = sorted(chargers, Comparator.comparing(charger -> charger.id().value()));
    Set<String> hubIds = new HashSet<>();
    for (Hub hub : hubs) {
      hubIds.add(hub.id().value());
    }
    Set<String> ids = new HashSet<>();
    for (Charger charger : sorted) {
      if (!ids.add(charger.id().value())) {
        throw new IllegalArgumentException("duplicate charger id: " + charger.id().value());
      }
      if (!charger.tenantId().equals(tenantId)) {
        throw new IllegalArgumentException("charger tenant does not match planner tenant");
      }
      if (!hubIds.contains(charger.hubId().value())) {
        throw new IllegalArgumentException("charger hub is not in the planner input");
      }
    }
    return sorted;
  }

  static List<TariffSchedule> copyTariffs(List<TariffSchedule> tariffs, List<Hub> hubs) {
    List<TariffSchedule> sorted =
        sorted(tariffs, Comparator.comparing(schedule -> schedule.hubId().value()));
    Set<String> hubIds = new HashSet<>();
    for (Hub hub : hubs) {
      hubIds.add(hub.id().value());
    }
    Set<String> seen = new HashSet<>();
    for (TariffSchedule schedule : sorted) {
      if (!seen.add(schedule.hubId().value())) {
        throw new IllegalArgumentException("duplicate tariff hub: " + schedule.hubId().value());
      }
      if (!hubIds.contains(schedule.hubId().value())) {
        throw new IllegalArgumentException("tariff hub is not in the planner input");
      }
    }
    return sorted;
  }

  private static <T> List<T> sorted(List<T> items, Comparator<T> order) {
    List<T> copy = new ArrayList<>(items);
    copy.sort(order);
    return List.copyOf(copy);
  }
}
