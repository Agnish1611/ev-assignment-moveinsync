package com.moveinsync.ev.domain.tariff;

import com.moveinsync.ev.domain.hub.HubId;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Immutable schedule of resolved UTC tariff windows for a hub. */
public final class TariffSchedule {

  private final HubId hubId;
  private final List<UtcTariffWindow> windows;

  public TariffSchedule(HubId hubId, List<UtcTariffWindow> windows) {
    this.hubId = Objects.requireNonNull(hubId, "HubId must not be null");
    Objects.requireNonNull(windows, "Windows list must not be null");

    List<UtcTariffWindow> sorted = new ArrayList<>(windows);
    sorted.sort(Comparator.comparing(UtcTariffWindow::startUtc));
    this.windows = Collections.unmodifiableList(sorted);
  }

  public HubId hubId() {
    return hubId;
  }

  public List<UtcTariffWindow> windows() {
    return windows;
  }

  /**
   * Finds the tariff window active at a given instant.
   *
   * @param instant time instant to query
   * @return Optional containing the active tariff window, or empty if none matches
   */
  public Optional<UtcTariffWindow> findTariffAt(Instant instant) {
    Objects.requireNonNull(instant, "Instant must not be null");
    return windows.stream().filter(w -> w.contains(instant)).findFirst();
  }

  /**
   * Calculates the charging cost in integer micro-units for an energy amount delivered uniformly
   * over the interval [start, end).
   *
   * <p>If the interval spans multiple tariff windows, energy is partitioned by duration and rounded
   * conservatively UP for each sub-interval.
   *
   * @param start start time of charging session
   * @param end end time of charging session
   * @param totalEnergyWh total energy delivered in Wh
   * @return total charging cost in integer micro-units
   */
  public long calculateCostMicros(Instant start, Instant end, long totalEnergyWh) {
    Objects.requireNonNull(start, "Start instant must not be null");
    Objects.requireNonNull(end, "End instant must not be null");
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
        // Proportion of energy delivered in this window (conservative round UP)
        long energyNum = Math.multiplyExact(totalEnergyWh, overlapSeconds);
        long windowEnergyWh = (energyNum + totalDurationSeconds - 1L) / totalDurationSeconds;
        long windowCost = window.calculateCostMicros(windowEnergyWh);
        totalCost = Math.addExact(totalCost, windowCost);
      }
    }

    return totalCost;
  }
}
