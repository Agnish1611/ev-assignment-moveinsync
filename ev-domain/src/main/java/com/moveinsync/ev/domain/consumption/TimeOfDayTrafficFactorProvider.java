package com.moveinsync.ev.domain.consumption;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Objects;

/**
 * Traffic factor provider that applies peak congestion multipliers based on local time of day.
 *
 * <p>Typically models morning rush hour (e.g. 08:00 to 11:00) and evening rush hour (e.g. 17:00 to
 * 21:00).
 */
public final class TimeOfDayTrafficFactorProvider implements TrafficFactorProvider {

  private final ZoneId localZone;
  private final LocalTime morningPeakStart;
  private final LocalTime morningPeakEnd;
  private final int morningPeakFactorBp;
  private final LocalTime eveningPeakStart;
  private final LocalTime eveningPeakEnd;
  private final int eveningPeakFactorBp;
  private final int offPeakFactorBp;

  public TimeOfDayTrafficFactorProvider(
      ZoneId localZone,
      LocalTime morningPeakStart,
      LocalTime morningPeakEnd,
      int morningPeakFactorBp,
      LocalTime eveningPeakStart,
      LocalTime eveningPeakEnd,
      int eveningPeakFactorBp,
      int offPeakFactorBp) {
    this.localZone = Objects.requireNonNull(localZone, "localZone must not be null");
    this.morningPeakStart =
        Objects.requireNonNull(morningPeakStart, "morningPeakStart must not be null");
    this.morningPeakEnd = Objects.requireNonNull(morningPeakEnd, "morningPeakEnd must not be null");
    this.eveningPeakStart =
        Objects.requireNonNull(eveningPeakStart, "eveningPeakStart must not be null");
    this.eveningPeakEnd = Objects.requireNonNull(eveningPeakEnd, "eveningPeakEnd must not be null");

    if (morningPeakFactorBp <= 0 || eveningPeakFactorBp <= 0 || offPeakFactorBp <= 0) {
      throw new IllegalArgumentException("Traffic factor basis points must be strictly positive");
    }

    this.morningPeakFactorBp = morningPeakFactorBp;
    this.eveningPeakFactorBp = eveningPeakFactorBp;
    this.offPeakFactorBp = offPeakFactorBp;
  }

  public static TimeOfDayTrafficFactorProvider defaultEveningShift(ZoneId zone) {
    return new TimeOfDayTrafficFactorProvider(
        zone,
        LocalTime.of(8, 0),
        LocalTime.of(11, 0),
        12_500, // 1.25x morning peak
        LocalTime.of(17, 0),
        LocalTime.of(21, 0),
        13_000, // 1.30x evening peak
        10_000 // 1.00x off-peak
        );
  }

  @Override
  public int getTrafficFactorBasisPoints(Instant instant) {
    Objects.requireNonNull(instant, "Instant must not be null");
    LocalTime localTime = ZonedDateTime.ofInstant(instant, localZone).toLocalTime();

    if (!localTime.isBefore(morningPeakStart) && localTime.isBefore(morningPeakEnd)) {
      return morningPeakFactorBp;
    }
    if (!localTime.isBefore(eveningPeakStart) && localTime.isBefore(eveningPeakEnd)) {
      return eveningPeakFactorBp;
    }
    return offPeakFactorBp;
  }
}
