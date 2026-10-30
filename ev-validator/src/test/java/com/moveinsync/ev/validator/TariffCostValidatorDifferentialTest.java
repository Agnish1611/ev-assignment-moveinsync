package com.moveinsync.ev.validator;

import static org.assertj.core.api.Assertions.assertThat;

import com.moveinsync.ev.domain.hub.HubId;
import com.moveinsync.ev.domain.tariff.TariffSchedule;
import com.moveinsync.ev.domain.tariff.TariffWindow;
import com.moveinsync.ev.domain.tariff.UtcTariffWindow;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Differential tests comparing the independent validator tariff walk against the domain
 * TariffSchedule (F-09).
 *
 * <p>Covers random multi-window sessions, daylight saving time (DST) transition days, and
 * midnight-crossing shifts.
 */
class TariffCostValidatorDifferentialTest {

  private final HubId hubId = HubId.of("HUB-TEST");

  @Test
  @DisplayName(
      "Differential test: 500 random sessions match TariffSchedule down to single micro-unit")
  void randomSessionsMatchDomainSchedule() {
    Random rng = new Random(12345);
    Instant base = Instant.parse("2026-10-30T00:00:00Z");

    // Construct 5 consecutive 4-hour tariff windows with varying rates
    List<UtcTariffWindow> windows = new ArrayList<>();
    for (int i = 0; i < 5; i++) {
      Instant wStart = base.plusSeconds(i * 14400L);
      Instant wEnd = wStart.plusSeconds(14400L);
      long rate = 50_000L + rng.nextInt(200_000);
      windows.add(new UtcTariffWindow(hubId, wStart, wEnd, rate));
    }
    TariffSchedule domainSchedule = new TariffSchedule(hubId, windows);

    for (int iter = 0; iter < 500; iter++) {
      long startOffset = rng.nextInt(50000);
      long duration = 300L + rng.nextInt(20000); // 5 min to ~5.5 hours
      Instant sStart = base.plusSeconds(startOffset);
      Instant sEnd = sStart.plusSeconds(duration);
      long energyWh = 500L + rng.nextInt(75_000); // 0.5 kWh to 75 kWh

      long domainCost = domainSchedule.calculateCostMicros(sStart, sEnd, energyWh);
      long validatorCost = TariffCostValidator.calculateCostMicros(sStart, sEnd, energyWh, windows);

      assertThat(validatorCost)
          .as("Iteration %d: validator cost must match domain cost exactly", iter)
          .isEqualTo(domainCost);
    }
  }

  @Test
  @DisplayName("Differential test: DST fall-back day (25-hour day) produces identical cost")
  void dstFallBackMatchesDomainSchedule() {
    ZoneId london = ZoneId.of("Europe/London");
    LocalDate fallBackDate = LocalDate.of(2026, 10, 25); // Autumn DST change in UK

    TariffWindow localNight =
        new TariffWindow(hubId, LocalTime.of(0, 0), LocalTime.of(8, 0), 80_000L);
    TariffWindow localDay =
        new TariffWindow(hubId, LocalTime.of(8, 0), LocalTime.of(20, 0), 180_000L);
    TariffWindow localEvening =
        new TariffWindow(hubId, LocalTime.of(20, 0), LocalTime.of(0, 0), 120_000L);

    List<UtcTariffWindow> windows =
        new ArrayList<>(
            List.of(
                localNight.toUtcWindow(fallBackDate, london),
                localDay.toUtcWindow(fallBackDate, london),
                localEvening.toUtcWindow(fallBackDate, london)));
    windows.sort((w1, w2) -> w1.startUtc().compareTo(w2.startUtc()));

    TariffSchedule domainSchedule = new TariffSchedule(hubId, windows);

    // Session spanning across the 01:00-02:00 repeated DST hour
    Instant sStart = windows.get(0).startUtc().plusSeconds(3600);
    Instant sEnd = sStart.plusSeconds(14400); // 4 hours
    long energyWh = 30_000L;

    long domainCost = domainSchedule.calculateCostMicros(sStart, sEnd, energyWh);
    long validatorCost = TariffCostValidator.calculateCostMicros(sStart, sEnd, energyWh, windows);

    assertThat(validatorCost).isEqualTo(domainCost);
  }

  @Test
  @DisplayName("Differential test: DST spring-forward day (23-hour day) produces identical cost")
  void dstSpringForwardMatchesDomainSchedule() {
    ZoneId london = ZoneId.of("Europe/London");
    LocalDate springDate = LocalDate.of(2026, 3, 29); // Spring DST change in UK

    TariffWindow localNight =
        new TariffWindow(hubId, LocalTime.of(0, 0), LocalTime.of(6, 0), 75_000L);
    TariffWindow localDay =
        new TariffWindow(hubId, LocalTime.of(6, 0), LocalTime.of(22, 0), 200_000L);

    List<UtcTariffWindow> windows =
        new ArrayList<>(
            List.of(
                localNight.toUtcWindow(springDate, london),
                localDay.toUtcWindow(springDate, london)));
    windows.sort((w1, w2) -> w1.startUtc().compareTo(w2.startUtc()));

    TariffSchedule domainSchedule = new TariffSchedule(hubId, windows);

    // Session spanning across the skipped hour
    Instant sStart = windows.get(0).startUtc().plusSeconds(1800);
    Instant sEnd = sStart.plusSeconds(10800); // 3 hours
    long energyWh = 40_000L;

    long domainCost = domainSchedule.calculateCostMicros(sStart, sEnd, energyWh);
    long validatorCost = TariffCostValidator.calculateCostMicros(sStart, sEnd, energyWh, windows);

    assertThat(validatorCost).isEqualTo(domainCost);
  }

  @Test
  @DisplayName(
      "Differential test: Midnight-crossing shift (22:00 to 06:00 next day) produces identical cost")
  void midnightCrossingMatchesDomainSchedule() {
    ZoneId kolkata = ZoneId.of("Asia/Kolkata");
    LocalDate shiftDate = LocalDate.of(2026, 10, 30);

    // Midnight crossing window: 22:00 to 06:00 next day
    TariffWindow nightWindow =
        new TariffWindow(hubId, LocalTime.of(22, 0), LocalTime.of(6, 0), 90_000L);
    UtcTariffWindow utcWindow = nightWindow.toUtcWindow(shiftDate, kolkata);

    TariffSchedule domainSchedule = new TariffSchedule(hubId, List.of(utcWindow));

    Instant sStart = utcWindow.startUtc().plusSeconds(1800);
    Instant sEnd = sStart.plusSeconds(7200);
    long energyWh = 25_000L;

    long domainCost = domainSchedule.calculateCostMicros(sStart, sEnd, energyWh);
    long validatorCost =
        TariffCostValidator.calculateCostMicros(sStart, sEnd, energyWh, List.of(utcWindow));

    assertThat(validatorCost).isEqualTo(domainCost);
  }
}
