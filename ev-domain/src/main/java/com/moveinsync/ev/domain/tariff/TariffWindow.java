package com.moveinsync.ev.domain.tariff;

import com.moveinsync.ev.domain.hub.HubId;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * An electricity tariff definition stored in the hub's local time zone.
 *
 * <p>Supports intraday windows, midnight-crossing windows (e.g. 22:00 to 06:00 next day), and
 * handles daylight saving time (DST) transitions safely when converting to UTC.
 *
 * @param hubId the hub to which this tariff applies
 * @param localStartTime local window start time
 * @param localEndTime local window end time
 * @param ratePerKwhMicros tariff rate per kWh in integer micro-units
 */
public record TariffWindow(
    HubId hubId, LocalTime localStartTime, LocalTime localEndTime, long ratePerKwhMicros) {

  public TariffWindow {
    Objects.requireNonNull(hubId, "HubId must not be null");
    Objects.requireNonNull(localStartTime, "localStartTime must not be null");
    Objects.requireNonNull(localEndTime, "localEndTime must not be null");
    if (ratePerKwhMicros < 0) {
      throw new IllegalArgumentException(
          "ratePerKwhMicros must be non-negative: " + ratePerKwhMicros);
    }
  }

  /**
   * Indicates whether this tariff window crosses midnight into the next calendar day.
   *
   * @return true if localStartTime is after localEndTime
   */
  public boolean isMidnightCrossing() {
    return localStartTime.isAfter(localEndTime);
  }

  /**
   * Converts this local tariff window into an exact UTC interval for the specified shift date and
   * time zone.
   *
   * <p>If this is a midnight-crossing window, the end boundary extends into the following calendar
   * day. Any DST offset change occurring on this date is naturally incorporated by the zone rules.
   *
   * @param localDate the date of the shift in the hub's local zone
   * @param timeZone the hub's local time zone
   * @return resolved UTC tariff window
   */
  public UtcTariffWindow toUtcWindow(LocalDate localDate, ZoneId timeZone) {
    Objects.requireNonNull(localDate, "localDate must not be null");
    Objects.requireNonNull(timeZone, "timeZone must not be null");

    ZonedDateTime startZdt = ZonedDateTime.of(localDate, localStartTime, timeZone);
    ZonedDateTime endZdt;

    if (isMidnightCrossing() || localStartTime.equals(localEndTime)) {
      endZdt = ZonedDateTime.of(localDate.plusDays(1), localEndTime, timeZone);
    } else {
      endZdt = ZonedDateTime.of(localDate, localEndTime, timeZone);
    }

    return new UtcTariffWindow(hubId, startZdt.toInstant(), endZdt.toInstant(), ratePerKwhMicros);
  }

  /**
   * Generates UTC tariff windows spanning a range of dates.
   *
   * @param startDateInclusive start date in hub-local zone
   * @param endDateInclusive end date in hub-local zone (inclusive)
   * @param timeZone hub local time zone
   * @return unmodifiable list of resolved UTC tariff windows
   */
  public List<UtcTariffWindow> toUtcWindows(
      LocalDate startDateInclusive, LocalDate endDateInclusive, ZoneId timeZone) {
    Objects.requireNonNull(startDateInclusive, "startDateInclusive must not be null");
    Objects.requireNonNull(endDateInclusive, "endDateInclusive must not be null");
    Objects.requireNonNull(timeZone, "timeZone must not be null");
    if (endDateInclusive.isBefore(startDateInclusive)) {
      throw new IllegalArgumentException("endDateInclusive must not be before startDateInclusive");
    }

    List<UtcTariffWindow> result = new ArrayList<>();
    LocalDate current = startDateInclusive;
    while (!current.isAfter(endDateInclusive)) {
      result.add(toUtcWindow(current, timeZone));
      current = current.plusDays(1);
    }
    return Collections.unmodifiableList(result);
  }
}
