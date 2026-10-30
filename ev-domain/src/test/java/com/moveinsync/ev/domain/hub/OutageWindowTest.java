package com.moveinsync.ev.domain.hub;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class OutageWindowTest {

  @Test
  @DisplayName("Valid half-open outage window rejects end before or equal to start")
  void intervalBoundaries() {
    Instant t100 = Instant.parse("2026-10-30T10:00:00Z");
    Instant t200 = Instant.parse("2026-10-30T11:00:00Z");

    OutageWindow window = new OutageWindow(t100, t200, "Scheduled maintenance");
    assertThat(window.startInclusive()).isEqualTo(t100);
    assertThat(window.endExclusive()).isEqualTo(t200);
    assertThat(window.reason()).isEqualTo("Scheduled maintenance");

    assertThatThrownBy(() -> new OutageWindow(t200, t100, "Bad"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new OutageWindow(t100, t100, "Zero"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("Overlaps checks half-open [start, end) interval semantics")
  void overlapSemantics() {
    Instant t10 = Instant.parse("2026-10-30T10:00:00Z");
    Instant t20 = Instant.parse("2026-10-30T10:20:00Z");
    OutageWindow window = new OutageWindow(t10, t20, "Outage");

    // Session completely before: [09:00, 10:00) -> does not overlap (boundary touching at 10:00)
    Instant t09 = Instant.parse("2026-10-30T09:00:00Z");
    assertThat(window.overlaps(t09, t10)).isFalse();

    // Session completely after: [10:20, 11:00) -> does not overlap (boundary touching at 10:20)
    Instant t30 = Instant.parse("2026-10-30T10:30:00Z");
    assertThat(window.overlaps(t20, t30)).isFalse();

    // Session overlapping by 1s: [10:19, 10:30)
    Instant t19 = Instant.parse("2026-10-30T10:19:59Z");
    assertThat(window.overlaps(t19, t30)).isTrue();

    // Session strictly inside: [10:05, 10:15)
    Instant t05 = Instant.parse("2026-10-30T10:05:00Z");
    Instant t15 = Instant.parse("2026-10-30T10:15:00Z");
    assertThat(window.overlaps(t05, t15)).isTrue();
  }
}
