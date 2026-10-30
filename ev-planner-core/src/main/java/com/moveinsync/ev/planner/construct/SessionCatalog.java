package com.moveinsync.ev.planner.construct;

import com.moveinsync.ev.domain.hub.Charger;
import com.moveinsync.ev.domain.hub.Hub;
import com.moveinsync.ev.domain.hub.OutageWindow;
import com.moveinsync.ev.domain.schedule.ChargingSession;
import com.moveinsync.ev.domain.tariff.TariffSchedule;
import com.moveinsync.ev.domain.tariff.UtcTariffWindow;
import com.moveinsync.ev.planner.config.SolverConfig;
import com.moveinsync.ev.planner.input.PlannerInput;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** Hub order, tariff-boundary starts, and the 60-second grid used while sliding a session. */
final class SessionCatalog {

  static List<Hub> hubs(PlannerInput input) {
    List<Hub> hubs = new ArrayList<>(input.hubs());
    hubs.sort(Comparator.comparing(hub -> hub.id().value()));
    return hubs;
  }

  static List<Charger> chargers(PlannerInput input, Hub hub) {
    List<Charger> chargers = new ArrayList<>();
    for (Charger charger : input.chargers()) {
      if (charger.hubId().equals(hub.id())) {
        chargers.add(charger);
      }
    }
    chargers.sort(Comparator.comparing(charger -> charger.id().value()));
    return chargers;
  }

  static Optional<Hub> hub(PlannerInput input, ChargingSession session) {
    for (Hub hub : input.hubs()) {
      if (hub.id().equals(session.hubId())) {
        return Optional.of(hub);
      }
    }
    return Optional.empty();
  }

  static Optional<Charger> charger(PlannerInput input, ChargingSession session) {
    for (Charger charger : input.chargers()) {
      if (charger.id().equals(session.chargerId()) && charger.hubId().equals(session.hubId())) {
        return Optional.of(charger);
      }
    }
    return Optional.empty();
  }

  static List<Instant> seeds(PlannerInput input, Hub hub, Instant earliest, Instant latest) {
    List<Instant> seeds = new ArrayList<>();
    seeds.add(earliest);
    addTariffStarts(input, hub, earliest, latest, seeds);
    addGrid(earliest, latest, seeds);
    return seeds;
  }

  static Optional<Instant> afterOutage(Charger charger, Instant start, Instant end) {
    Instant next = start;
    for (OutageWindow outage : charger.outageWindows()) {
      if (outage.overlaps(start, end) && outage.endExclusive().isAfter(next)) {
        next = outage.endExclusive();
      }
    }
    if (next.equals(start)) {
      return Optional.empty();
    }
    return Optional.of(next);
  }

  private static void addTariffStarts(
      PlannerInput input, Hub hub, Instant earliest, Instant latest, List<Instant> seeds) {
    for (TariffSchedule schedule : input.tariffs()) {
      if (!schedule.hubId().equals(hub.id())) {
        continue;
      }
      for (UtcTariffWindow window : schedule.windows()) {
        Instant start = window.startUtc();
        if (!start.isBefore(earliest) && start.isBefore(latest)) {
          seeds.add(start);
        }
      }
    }
  }

  private static void addGrid(Instant earliest, Instant latest, List<Instant> seeds) {
    long cursor = earliest.getEpochSecond();
    int width = SolverConfig.FLOW_SLOT_SECONDS;
    long remainder = cursor % width;
    if (remainder != 0L) {
      cursor += width - remainder;
    }
    int added = 0;
    while (cursor < latest.getEpochSecond() && added < SolverConfig.FLOW_START_CAP) {
      seeds.add(Instant.ofEpochSecond(cursor));
      cursor += width;
      added++;
    }
  }
}
