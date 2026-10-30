package com.moveinsync.ev.planner.construct;

import com.moveinsync.ev.domain.hub.HubId;
import com.moveinsync.ev.domain.tariff.TariffSchedule;
import com.moveinsync.ev.domain.tariff.UtcTariffWindow;
import com.moveinsync.ev.planner.input.PlannerInput;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Integer tariff walk for a charge stop. The same split, in the same order, is what the validator
 * recomputes. An empty tariff list costs nothing, and the validator then skips the cost check.
 */
final class ChargePrice {

  static long micros(PlannerInput input, HubId hubId, Instant start, Instant end, long energyWh) {
    List<UtcTariffWindow> windows = new ArrayList<>();
    for (TariffSchedule schedule : input.tariffs()) {
      if (schedule.hubId().equals(hubId)) {
        for (UtcTariffWindow window : schedule.windows()) {
          windows.add(window);
        }
      }
    }
    if (windows.isEmpty() || energyWh <= 0L) {
      return 0L;
    }
    return quote(start, end, energyWh, windows);
  }

  private static long quote(
      Instant start, Instant end, long totalEnergyWh, List<UtcTariffWindow> windows) {
    long totalDurationSeconds = Duration.between(start, end).getSeconds();
    long totalCost = 0L;
    for (UtcTariffWindow window : windows) {
      if (!window.overlaps(start, end)) {
        continue;
      }
      Instant overlapStart = window.startUtc().isAfter(start) ? window.startUtc() : start;
      Instant overlapEnd = window.endUtc().isBefore(end) ? window.endUtc() : end;
      long overlapSeconds = Duration.between(overlapStart, overlapEnd).getSeconds();
      if (overlapSeconds > 0L) {
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
