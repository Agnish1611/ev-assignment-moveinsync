package com.moveinsync.ev.domain.tariff;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.moveinsync.ev.domain.hub.HubId;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TariffWindowTest {

  private final HubId hubId = HubId.of("HUB-CENTRAL");

  @Test
  @DisplayName("Standard daytime tariff window converts accurately to UTC")
  void standardDaytimeWindowToUtc() {
    // 09:00 to 17:00 in Asia/Kolkata (+05:30)
    ZoneId zone = ZoneId.of("Asia/Kolkata");
    LocalDate date = LocalDate.of(2026, 10, 30);
    TariffWindow window =
        new TariffWindow(hubId, LocalTime.of(9, 0), LocalTime.of(17, 0), 150_000L);

    assertThat(window.isMidnightCrossing()).isFalse();

    UtcTariffWindow utcWindow = window.toUtcWindow(date, zone);
    assertThat(utcWindow.startUtc()).isEqualTo(Instant.parse("2026-10-30T03:30:00Z"));
    assertThat(utcWindow.endUtc()).isEqualTo(Instant.parse("2026-10-30T11:30:00Z"));
    assertThat(utcWindow.durationSeconds()).isEqualTo(8 * 3600);
  }

  @Test
  @DisplayName("Midnight-crossing shift (22:00 to 06:00 next day) correctly spans calendar dates")
  void midnightCrossingShiftWindow() {
    // 22:00 to 06:00 in Asia/Kolkata (+05:30)
    ZoneId zone = ZoneId.of("Asia/Kolkata");
    LocalDate shiftDate = LocalDate.of(2026, 10, 30);
    TariffWindow nightWindow =
        new TariffWindow(hubId, LocalTime.of(22, 0), LocalTime.of(6, 0), 80_000L);

    assertThat(nightWindow.isMidnightCrossing()).isTrue();

    UtcTariffWindow utcWindow = nightWindow.toUtcWindow(shiftDate, zone);
    // 22:00 IST on 2026-10-30 is 16:30 UTC on 2026-10-30
    assertThat(utcWindow.startUtc()).isEqualTo(Instant.parse("2026-10-30T16:30:00Z"));
    // 06:00 IST on 2026-10-31 is 00:30 UTC on 2026-10-31
    assertThat(utcWindow.endUtc()).isEqualTo(Instant.parse("2026-10-31T00:30:00Z"));
    assertThat(utcWindow.durationSeconds()).isEqualTo(8 * 3600);
  }

  @Test
  @DisplayName(
      "DST transition: Autumn Fall-Back day produces 7-hour duration for 00:00-06:00 local")
  void dstFallBackTransitionDay() {
    // In Europe/London on 2026-10-25, 02:00 BST falls back to 01:00 GMT (1 extra hour)
    ZoneId london = ZoneId.of("Europe/London");
    LocalDate fallBackDate = LocalDate.of(2026, 10, 25);
    TariffWindow window = new TariffWindow(hubId, LocalTime.of(0, 0), LocalTime.of(6, 0), 100_000L);

    UtcTariffWindow utc = window.toUtcWindow(fallBackDate, london);

    // 00:00 BST is 2026-10-24T23:00:00Z
    assertThat(utc.startUtc()).isEqualTo(Instant.parse("2026-10-24T23:00:00Z"));
    // 06:00 GMT is 2026-10-25T06:00:00Z
    assertThat(utc.endUtc()).isEqualTo(Instant.parse("2026-10-25T06:00:00Z"));

    // Exactly 7 hours (25,200 seconds) due to the repeated hour
    assertThat(utc.durationSeconds()).isEqualTo(7 * 3600);
  }

  @Test
  @DisplayName(
      "DST transition: Spring Spring-Forward day produces 5-hour duration for 00:00-06:00 local")
  void dstSpringForwardTransitionDay() {
    // In Europe/London on 2026-03-29, 01:00 GMT springs forward to 02:00 BST (1 hour skipped)
    ZoneId london = ZoneId.of("Europe/London");
    LocalDate springForwardDate = LocalDate.of(2026, 3, 29);
    TariffWindow window = new TariffWindow(hubId, LocalTime.of(0, 0), LocalTime.of(6, 0), 100_000L);

    UtcTariffWindow utc = window.toUtcWindow(springForwardDate, london);

    // 00:00 GMT is 2026-03-29T00:00:00Z
    assertThat(utc.startUtc()).isEqualTo(Instant.parse("2026-03-29T00:00:00Z"));
    // 06:00 BST is 2026-03-29T05:00:00Z
    assertThat(utc.endUtc()).isEqualTo(Instant.parse("2026-03-29T05:00:00Z"));

    // Exactly 5 hours (18,000 seconds) due to the skipped hour
    assertThat(utc.durationSeconds()).isEqualTo(5 * 3600);
  }

  @Test
  @DisplayName("Midnight-crossing window crossing an Autumn Fall-Back night captures 9 hours total")
  void midnightCrossingAcrossFallBackNight() {
    // Window from 22:00 to 06:00 on the night of 2026-10-24 into 2026-10-25
    ZoneId london = ZoneId.of("Europe/London");
    LocalDate date = LocalDate.of(2026, 10, 24);
    TariffWindow nightWindow =
        new TariffWindow(hubId, LocalTime.of(22, 0), LocalTime.of(6, 0), 90_000L);

    UtcTariffWindow utc = nightWindow.toUtcWindow(date, london);
    // 22:00 BST on 2026-10-24 is 21:00 UTC
    assertThat(utc.startUtc()).isEqualTo(Instant.parse("2026-10-24T21:00:00Z"));
    // 06:00 GMT on 2026-10-25 is 06:00 UTC
    assertThat(utc.endUtc()).isEqualTo(Instant.parse("2026-10-25T06:00:00Z"));

    // 21:00Z to 06:00Z is 9 hours
    assertThat(Duration.between(utc.startUtc(), utc.endUtc()).toHours()).isEqualTo(9);
  }

  @Test
  @DisplayName("Cost calculation in micro-units with conservative round UP")
  void costCalculationConservativeRoundUp() {
    UtcTariffWindow utcWindow =
        new UtcTariffWindow(
            hubId,
            Instant.parse("2026-10-30T00:00:00Z"),
            Instant.parse("2026-10-30T08:00:00Z"),
            200_000L); // 200,000 micros/kWh = 0.20 currency units/kWh

    // 50,000 Wh = 50 kWh -> 50 * 200,000 = 10,000,000 micros
    assertThat(utcWindow.calculateCostMicros(50_000L)).isEqualTo(10_000_000L);

    // 0 Wh -> 0 cost
    assertThat(utcWindow.calculateCostMicros(0L)).isEqualTo(0L);

    // 1 Wh at 200,000 micros/kWh -> 1 * 200,000 / 1000 = 200 micros exactly
    assertThat(utcWindow.calculateCostMicros(1L)).isEqualTo(200L);

    // 1 Wh at 150,001 micros/kWh -> (150,001 + 999) / 1000 = 151 micros (conservative round UP)
    UtcTariffWindow unevenWindow =
        new UtcTariffWindow(
            hubId,
            Instant.parse("2026-10-30T00:00:00Z"),
            Instant.parse("2026-10-30T08:00:00Z"),
            150_001L);
    assertThat(unevenWindow.calculateCostMicros(1L)).isEqualTo(151L);
  }

  @Test
  @DisplayName("TariffSchedule calculates cost across multiple windows")
  void tariffScheduleCrossWindowCalculation() {
    Instant t0 = Instant.parse("2026-10-30T00:00:00Z");
    Instant t1 = Instant.parse("2026-10-30T04:00:00Z");
    Instant t2 = Instant.parse("2026-10-30T08:00:00Z");

    UtcTariffWindow w1 = new UtcTariffWindow(hubId, t0, t1, 100_000L); // 100,000 micros/kWh
    UtcTariffWindow w2 = new UtcTariffWindow(hubId, t1, t2, 200_000L); // 200,000 micros/kWh

    TariffSchedule schedule = new TariffSchedule(hubId, List.of(w1, w2));

    // Session spanning t0 to t2 (8 hours total, 4 hours in each window) delivering 40,000 Wh
    // 20,000 Wh in w1 @ 100k -> 2,000,000 micros
    // 20,000 Wh in w2 @ 200k -> 4,000,000 micros
    // Total = 6,000,000 micros
    long cost = schedule.calculateCostMicros(t0, t2, 40_000L);
    assertThat(cost).isEqualTo(6_000_000L);
  }

  @Test
  @DisplayName("TariffWindow rejects negative rates and null arguments")
  void tariffWindowValidation() {
    assertThatThrownBy(() -> new TariffWindow(null, LocalTime.NOON, LocalTime.MIDNIGHT, 100L))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new TariffWindow(hubId, LocalTime.NOON, LocalTime.MIDNIGHT, -1L))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new TariffWindow(hubId, null, LocalTime.MIDNIGHT, 100L))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new TariffWindow(hubId, LocalTime.NOON, null, 100L))
        .isInstanceOf(NullPointerException.class);

    TariffWindow fullDay = new TariffWindow(hubId, LocalTime.MIDNIGHT, LocalTime.MIDNIGHT, 100L);
    UtcTariffWindow fullUtc = fullDay.toUtcWindow(LocalDate.of(2026, 10, 30), ZoneId.of("UTC"));
    assertThat(fullUtc.durationSeconds()).isEqualTo(86400L);

    assertThatThrownBy(
            () ->
                fullDay.toUtcWindows(
                    LocalDate.of(2026, 10, 30), LocalDate.of(2026, 10, 20), ZoneId.of("UTC")))
        .isInstanceOf(IllegalArgumentException.class);

    // UtcTariffWindow validations
    Instant t0 = Instant.parse("2026-10-30T00:00:00Z");
    Instant t1 = Instant.parse("2026-10-30T01:00:00Z");
    assertThatThrownBy(() -> new UtcTariffWindow(hubId, t1, t0, 100L))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new UtcTariffWindow(hubId, t0, t1, -1L))
        .isInstanceOf(IllegalArgumentException.class);

    UtcTariffWindow freeWin = new UtcTariffWindow(hubId, t0, t1, 0L);
    assertThat(freeWin.calculateCostMicros(5000L)).isZero();
    assertThatThrownBy(() -> freeWin.calculateCostMicros(-10L))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
