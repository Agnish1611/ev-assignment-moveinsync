package com.moveinsync.ev.sim;

import com.moveinsync.ev.domain.hub.HubId;
import com.moveinsync.ev.domain.tariff.TariffSchedule;
import com.moveinsync.ev.domain.tariff.TariffWindow;
import com.moveinsync.ev.planner.input.PlannerInput;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Objects;

/**
 * Seeded shift builder. Time O(C + T) and space O(C + T) for C cabs and T trips. Two calls with the
 * same profile and seed return plans with the same input hash. {@link ShiftProfile#EVENING_SCALE}
 * is the published case-study snapshot, so its seed is ignored.
 */
public final class ScenarioGenerator {

  private static final Instant EVENING = Instant.parse("2026-10-30T18:00:00Z");
  private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");
  private static final LocalDate FALLBACK_DAY = LocalDate.of(2026, 11, 1);
  private static final long RATE_MICROS = 2_000_000L;

  private ScenarioGenerator() {
    throw new IllegalStateException("utility");
  }

  public static PlannerInput generate(ShiftProfile profile, long seed) {
    Objects.requireNonNull(profile, "profile must not be null");
    return switch (profile) {
      case EVENING_SCALE -> ScaleScenario.build();
      case TIGHT_BATTERY -> tight(seed);
      case CHARGER_SCARCE -> scarce(seed);
      case PEAK_HEAVY -> peak(seed);
      case DST_CROSSING -> dst(seed);
    };
  }

  private static PlannerInput tight(long seed) {
    int offset = offsetSeconds(seed);
    int lowCab = (int) Math.floorMod(seed, 2L);
    ShiftDraft draft = ShiftDraft.open("tenant-tight", EVENING, EVENING.plusSeconds(21_600), 1);
    draft.cab(0, lowCab == 0 ? 12_000L : 50_000L);
    draft.cab(1, lowCab == 0 ? 50_000L : 12_000L);
    draft.trip(0, EVENING.plusSeconds(600L + offset), 2_000, 600);
    draft.trip(1, EVENING.plusSeconds(3_000L + offset), 40_000, 2_000);
    return draft.build();
  }

  private static PlannerInput scarce(long seed) {
    int offset = offsetSeconds(seed);
    ShiftDraft draft = ShiftDraft.open("tenant-scarce", EVENING, EVENING.plusSeconds(21_600), 1);
    for (int cab = 0; cab < 3; cab++) {
      draft.cab(cab, 20_000L);
    }
    for (int trip = 0; trip < 3; trip++) {
      draft.trip(trip, EVENING.plusSeconds(600L + offset + (long) trip * 900L), 8_000, 900);
    }
    return draft.build();
  }

  private static PlannerInput peak(long seed) {
    int offset = offsetSeconds(seed);
    Instant pickup = EVENING.plusSeconds(600L + offset);
    ShiftDraft draft = ShiftDraft.open("tenant-peak", EVENING, EVENING.plusSeconds(21_600), 2);
    draft.cab(0, 50_000L);
    draft.cab(1, 50_000L);
    for (int trip = 0; trip < 6; trip++) {
      draft.trip(trip, pickup, 2_000, 600);
    }
    return draft.build();
  }

  private static PlannerInput dst(long seed) {
    int offset = offsetSeconds(seed);
    Instant start = Instant.parse("2026-11-01T04:30:00Z");
    Instant end = Instant.parse("2026-11-01T09:30:00Z");
    ShiftDraft draft = ShiftDraft.open("tenant-dst", start, end, NEW_YORK, 1);
    draft.cab(0, 50_000L);
    draft.cab(1, 50_000L);
    draft.trip(0, Instant.parse("2026-11-01T05:30:00Z").plusSeconds(offset), 2_000, 600);
    draft.trip(1, Instant.parse("2026-11-01T06:30:00Z").plusSeconds(offset), 2_000, 600);
    HubId hubId = draft.hubId();
    TariffWindow local =
        new TariffWindow(hubId, LocalTime.of(1, 0), LocalTime.of(3, 0), RATE_MICROS);
    draft.tariff(
        new TariffSchedule(hubId, local.toUtcWindows(FALLBACK_DAY, FALLBACK_DAY, NEW_YORK)));
    return draft.build();
  }

  private static int offsetSeconds(long seed) {
    return (int) Math.floorMod(seed, 60L);
  }
}
