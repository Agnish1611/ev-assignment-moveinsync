package com.moveinsync.ev.planner.construct;

import com.moveinsync.ev.domain.hub.Charger;
import com.moveinsync.ev.domain.hub.ChargerId;
import com.moveinsync.ev.domain.hub.Hub;
import com.moveinsync.ev.planner.input.PlannerInput;
import java.util.Optional;

/**
 * A copy of a fleet whose named hub has one extra operational plug. Time O(C + T + P). The copy is
 * used only to test whether that plug would have accepted a backup trip.
 */
final class RelaxedFleet {

  private static final String PLUG_PREFIX = "CHG-RELAX-";

  private RelaxedFleet() {
    throw new IllegalStateException("utility");
  }

  static Optional<PlannerInput> oneMorePlug(PlannerInput input, Hub hub) {
    Optional<Hub> current = current(input, hub);
    if (current.isEmpty()) {
      return Optional.empty();
    }
    Hub found = current.orElseThrow();
    Optional<Charger> template = template(input, found);
    if (template.isEmpty()) {
      return Optional.empty();
    }
    String plugId = PLUG_PREFIX + found.id().value();
    if (taken(input, plugId) || found.chargerCount() == Integer.MAX_VALUE) {
      return Optional.empty();
    }
    return Optional.of(copy(input, found, template.orElseThrow(), plugId));
  }

  private static Optional<Hub> current(PlannerInput input, Hub hub) {
    for (Hub existing : input.hubs()) {
      if (existing.id().equals(hub.id())) {
        return Optional.of(existing);
      }
    }
    return Optional.empty();
  }

  private static Optional<Charger> template(PlannerInput input, Hub hub) {
    Charger chosen = null;
    for (Charger charger : input.chargers()) {
      if (charger.hubId().equals(hub.id())
          && charger.status().isAvailable()
          && better(charger, chosen)) {
        chosen = charger;
      }
    }
    return Optional.ofNullable(chosen);
  }

  private static boolean better(Charger charger, Charger chosen) {
    if (chosen == null) {
      return true;
    }
    if (charger.powerKw() != chosen.powerKw()) {
      return charger.powerKw() > chosen.powerKw();
    }
    return charger.id().value().compareTo(chosen.id().value()) < 0;
  }

  private static boolean taken(PlannerInput input, String plugId) {
    for (Charger charger : input.chargers()) {
      if (charger.id().value().equals(plugId)) {
        return true;
      }
    }
    return false;
  }

  private static PlannerInput copy(PlannerInput input, Hub hub, Charger template, String plugId) {
    PlannerInput.Builder builder = base(input);
    for (Hub existing : input.hubs()) {
      builder.addHub(existing.id().equals(hub.id()) ? larger(existing) : existing);
    }
    input.chargers().forEach(builder::addCharger);
    builder.addCharger(extra(template, plugId));
    input.cabs().forEach(builder::addCab);
    input.trips().forEach(builder::addTrip);
    input.tariffs().forEach(builder::addTariff);
    return builder.build();
  }

  private static PlannerInput.Builder base(PlannerInput input) {
    return PlannerInput.builder()
        .tenantId(input.tenantId())
        .horizon(input.horizonStart(), input.horizonEnd())
        .consumptionModel(input.consumptionModel())
        .distanceProvider(input.distanceProvider())
        .chargingCurve(input.chargingCurve())
        .acOn(input.acOn())
        .idlePowerW(input.idlePowerW());
  }

  private static Hub larger(Hub hub) {
    return new Hub(
        hub.tenantId(),
        hub.id(),
        hub.name(),
        hub.location(),
        hub.timeZone(),
        hub.chargerCount() + 1);
  }

  private static Charger extra(Charger template, String plugId) {
    return new Charger(
        template.tenantId(),
        ChargerId.of(plugId),
        template.hubId(),
        template.powerKw(),
        template.status(),
        template.outageWindows());
  }
}
