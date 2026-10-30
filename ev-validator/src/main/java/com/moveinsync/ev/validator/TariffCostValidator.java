package com.moveinsync.ev.validator;

import com.moveinsync.ev.domain.hub.Hub;
import com.moveinsync.ev.domain.hub.HubId;
import com.moveinsync.ev.domain.schedule.ChargingSession;
import com.moveinsync.ev.domain.schedule.FleetPlan;
import com.moveinsync.ev.domain.schedule.RouteActivity;
import com.moveinsync.ev.domain.schedule.VehicleSchedule;
import com.moveinsync.ev.domain.tariff.TariffWindow;
import com.moveinsync.ev.domain.tariff.UtcTariffWindow;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Validates charging session financial costs against ground-truth electricity tariffs.
 *
 * <p>Implements an independent tariff walk over UTC windows in integer micro-units without floating
 * point arithmetic. Rounding is conservative UP.
 */
final class TariffCostValidator {

  private final ValidationInput input;

  TariffCostValidator(ValidationInput input) {
    this.input = Objects.requireNonNull(input, "ValidationInput must not be null");
  }

  List<PlanViolation> validate(FleetPlan candidate) {
    List<PlanViolation> violations = new ArrayList<>();
    Map<HubId, List<UtcTariffWindow>> resolvedTariffs = resolveAllTariffs();

    for (VehicleSchedule schedule : candidate.vehicleSchedules()) {
      for (RouteActivity activity : schedule.activities()) {
        if (activity instanceof ChargingSession session) {
          validateSessionCost(session, schedule, resolvedTariffs, violations);
        }
      }
    }
    return violations;
  }

  private void validateSessionCost(
      ChargingSession session,
      VehicleSchedule schedule,
      Map<HubId, List<UtcTariffWindow>> resolvedTariffs,
      List<PlanViolation> violations) {
    List<UtcTariffWindow> hubWindows = resolvedTariffs.get(session.hubId());
    if (hubWindows == null || hubWindows.isEmpty()) {
      return; // No tariffs specified for this hub
    }

    long totalEnergyWh = session.targetSocWh() - session.startSocWh();
    long expectedCost =
        calculateCostMicros(session.startTime(), session.endTime(), totalEnergyWh, hubWindows);

    if (session.costMicros() != expectedCost) {
      violations.add(
          new PlanViolation(
              RuleId.COST_MISMATCH,
              Optional.of(schedule.vehicleId()),
              Optional.empty(),
              Optional.of(session.hubId()),
              Optional.of(session.startTime()),
              "Charging session "
                  + session.sessionId()
                  + " on vehicle "
                  + schedule.vehicleId()
                  + " claimed cost ("
                  + session.costMicros()
                  + " µ) does not match ground truth tariff calculation ("
                  + expectedCost
                  + " µ)"));
    }
  }

  private Map<HubId, List<UtcTariffWindow>> resolveAllTariffs() {
    Map<HubId, List<UtcTariffWindow>> map = new HashMap<>();

    for (UtcTariffWindow utc : input.utcTariffs()) {
      map.computeIfAbsent(utc.hubId(), k -> new ArrayList<>()).add(utc);
    }

    for (TariffWindow localWindow : input.tariffs()) {
      Optional<Hub> hubOpt = input.findHub(localWindow.hubId());
      if (hubOpt.isPresent()) {
        ZoneId zone = hubOpt.get().timeZone();
        LocalDate startDate = input.horizonStart().atZone(zone).toLocalDate().minusDays(1);
        LocalDate endDate = input.horizonEnd().atZone(zone).toLocalDate().plusDays(1);
        List<UtcTariffWindow> resolved = localWindow.toUtcWindows(startDate, endDate, zone);
        for (UtcTariffWindow r : resolved) {
          if (r.overlaps(input.horizonStart(), input.horizonEnd())) {
            map.computeIfAbsent(localWindow.hubId(), k -> new ArrayList<>()).add(r);
          }
        }
      }
    }

    for (List<UtcTariffWindow> list : map.values()) {
      list.sort(Comparator.comparing(UtcTariffWindow::startUtc));
    }
    return map;
  }

  /**
   * Calculates charging cost in integer micro-units over resolved UTC tariff windows.
   *
   * @param start start instant of charging
   * @param end end instant of charging
   * @param totalEnergyWh energy delivered in Wh
   * @param windows resolved UTC tariff windows
   * @return total cost in integer micro-units
   */
  public static long calculateCostMicros(
      Instant start, Instant end, long totalEnergyWh, List<UtcTariffWindow> windows) {
    Objects.requireNonNull(start, "Start instant must not be null");
    Objects.requireNonNull(end, "End instant must not be null");
    Objects.requireNonNull(windows, "Windows list must not be null");

    if (!start.isBefore(end)) {
      throw new IllegalArgumentException("Start must be before end: " + start + " -> " + end);
    }
    if (totalEnergyWh <= 0) {
      return 0L;
    }

    long totalDurationSeconds = Duration.between(start, end).getSeconds();
    long totalCost = 0L;

    for (UtcTariffWindow window : windows) {
      if (!window.overlaps(start, end)) {
        continue;
      }
      Instant overlapStart = window.startUtc().isAfter(start) ? window.startUtc() : start;
      Instant overlapEnd = window.endUtc().isBefore(end) ? window.endUtc() : end;
      long overlapSeconds = Duration.between(overlapStart, overlapEnd).getSeconds();

      if (overlapSeconds > 0) {
        long energyNum = Math.multiplyExact(totalEnergyWh, overlapSeconds);
        long windowEnergyWh = (energyNum + totalDurationSeconds - 1L) / totalDurationSeconds;
        long costNum = Math.multiplyExact(windowEnergyWh, window.ratePerKwhMicros());
        long windowCost = (costNum + 999L) / 1000L;
        totalCost = Math.addExact(totalCost, windowCost);
      }
    }

    return totalCost;
  }
}
