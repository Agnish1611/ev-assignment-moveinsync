package com.moveinsync.ev.domain.trip;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.moveinsync.ev.domain.location.Location;
import com.moveinsync.ev.domain.tenant.TenantId;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TripTest {

  private final Location pickupLoc = Location.of("LOC-PICKUP", "Pickup Point A");
  private final Location dropoffLoc = Location.of("LOC-DROPOFF", "Office Dropoff");

  @Test
  @DisplayName("Valid trip creation with correct schedule calculations")
  void validTripCreation() {
    Instant start = Instant.parse("2026-10-30T10:00:00Z");
    Instant latestFinish = Instant.parse("2026-10-30T11:00:00Z");
    int durationSeconds = 1800; // 30 minutes

    Trip trip =
        new Trip(
            TenantId.of("tenant-fixture"),
            TripId.of("TRIP-001"),
            start,
            pickupLoc,
            dropoffLoc,
            15_000, // 15 km
            durationSeconds,
            latestFinish,
            "EMP-OPQ-8821");

    assertThat(trip.plannedDropoffTs()).isEqualTo(Instant.parse("2026-10-30T10:30:00Z"));
    // 30 minutes slack between planned dropoff (10:30) and latest finish (11:00)
    assertThat(trip.slackSeconds()).isEqualTo(1800L);
  }

  @Test
  @DisplayName("Trip rejects planned dropoff exceeding latest finish deadline")
  void rejectDropoffExceedingDeadline() {
    Instant start = Instant.parse("2026-10-30T10:00:00Z");
    Instant latestFinish = Instant.parse("2026-10-30T10:20:00Z");
    int durationSeconds = 1800; // 30 min > 20 min deadline

    assertThatThrownBy(
            () ->
                new Trip(
                    TenantId.of("tenant-fixture"),
                    TripId.of("TRIP-002"),
                    start,
                    pickupLoc,
                    dropoffLoc,
                    15_000,
                    durationSeconds,
                    latestFinish,
                    "EMP-991"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("exceeds latestFinishTs");
  }

  @Test
  @DisplayName("Trip rejects employeeRef containing personal data (email or phone)")
  void rejectPiiInEmployeeRef() {
    Instant start = Instant.parse("2026-10-30T10:00:00Z");
    Instant latestFinish = Instant.parse("2026-10-30T11:00:00Z");

    // Email pattern rejected
    assertThatThrownBy(
            () ->
                new Trip(
                    TenantId.of("tenant-fixture"),
                    TripId.of("TRIP-PII-1"),
                    start,
                    pickupLoc,
                    dropoffLoc,
                    10_000,
                    1200,
                    latestFinish,
                    "john.doe@company.com"))
        .isInstanceOf(IllegalArgumentException.class);

    // Phone number pattern rejected
    assertThatThrownBy(
            () ->
                new Trip(
                    TenantId.of("tenant-fixture"),
                    TripId.of("TRIP-PII-2"),
                    start,
                    pickupLoc,
                    dropoffLoc,
                    10_000,
                    1200,
                    latestFinish,
                    "+1-555-019-2834"))
        .isInstanceOf(IllegalArgumentException.class);

    // Blank rejected
    assertThatThrownBy(
            () ->
                new Trip(
                    TenantId.of("tenant-fixture"),
                    TripId.of("TRIP-PII-3"),
                    start,
                    pickupLoc,
                    dropoffLoc,
                    10_000,
                    1200,
                    latestFinish,
                    "   "))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("Location toString preserves privacy by only emitting opaque ID")
  void locationPrivacyToString() {
    Location loc = Location.of("LOC-42", "Personal Residence", 12_971_600, 77_594_600);
    assertThat(loc.toString()).isEqualTo("LOC-42");
    assertThat(loc.toString()).doesNotContain("Personal Residence");
  }
}
