package com.moveinsync.ev.domain.distance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.moveinsync.ev.domain.location.Location;
import com.moveinsync.ev.domain.location.LocationId;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DistanceProviderTest {

  private final Location locA = Location.of("LOC-A", "Location A");
  private final Location locB = Location.of("LOC-B", "Location B");
  private final Location locC = Location.of("LOC-C", "Location C");

  @Test
  @DisplayName("MatrixDistanceProvider resolves stored distance and duration")
  void matrixDistanceLookup() {
    MatrixDistanceProvider.LocationPair pair =
        new MatrixDistanceProvider.LocationPair(LocationId.of("LOC-A"), LocationId.of("LOC-B"));
    DistanceDuration distanceDuration = DistanceDuration.of(12_000, 1200);

    MatrixDistanceProvider provider = new MatrixDistanceProvider(Map.of(pair, distanceDuration));

    // A -> B
    DistanceDuration result = provider.getDistanceAndDuration(locA, locB);
    assertThat(result.distanceMeters()).isEqualTo(12_000);
    assertThat(result.durationSeconds()).isEqualTo(1200);
    assertThat(provider.entries()).hasSize(1);
    assertThat(provider.fallback()).isEmpty();

    // A -> A is zero distance and zero duration
    DistanceDuration self = provider.getDistanceAndDuration(locA, locA);
    assertThat(self.distanceMeters()).isEqualTo(0);
    assertThat(self.durationSeconds()).isEqualTo(0);
  }

  @Test
  @DisplayName("MatrixDistanceProvider delegates to fallback when entry is missing")
  void matrixFallbackProvider() {
    DistanceProvider fallback = new ConstantSpeedDistanceProvider(8_000, 10);
    MatrixDistanceProvider provider = new MatrixDistanceProvider(Map.of(), fallback);

    DistanceDuration result = provider.getDistanceAndDuration(locA, locC);
    assertThat(provider.fallback()).contains(fallback);
    assertThat(result.distanceMeters()).isEqualTo(8_000);
    // 8,000 / 10 = 800s
    assertThat(result.durationSeconds()).isEqualTo(800);
  }

  @Test
  @DisplayName("MatrixDistanceProvider throws exception when route missing and no fallback")
  void missingRouteThrowsException() {
    MatrixDistanceProvider provider = new MatrixDistanceProvider(Map.of());
    assertThatThrownBy(() -> provider.getDistanceAndDuration(locA, locB))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("No distance entry");
  }

  @Test
  @DisplayName("Matrix entries are sorted by origin id then destination id")
  void entriesAreSorted() {
    MatrixDistanceProvider.LocationPair later =
        new MatrixDistanceProvider.LocationPair(LocationId.of("LOC-B"), LocationId.of("LOC-A"));
    MatrixDistanceProvider.LocationPair earlier =
        new MatrixDistanceProvider.LocationPair(LocationId.of("LOC-A"), LocationId.of("LOC-B"));
    MatrixDistanceProvider provider =
        new MatrixDistanceProvider(
            Map.of(later, DistanceDuration.of(1, 1), earlier, DistanceDuration.of(2, 2)));

    assertThat(provider.entries()).hasSize(2);
    assertThat(provider.entries().get(0).getKey().from().value()).isEqualTo("LOC-A");
    assertThat(provider.entries().get(1).getKey().from().value()).isEqualTo("LOC-B");
  }

  @Test
  @DisplayName("ConstantSpeedDistanceProvider calculates duration with conservative round UP")
  void constantSpeedDurationRounding() {
    // 10,001 metres at 10 m/s = 1000.1 seconds -> rounds UP to 1001 seconds
    ConstantSpeedDistanceProvider provider = new ConstantSpeedDistanceProvider(10_001, 10);
    assertThat(provider.defaultDistanceMeters()).isEqualTo(10_001);
    assertThat(provider.speedMetersPerSecond()).isEqualTo(10);
    DistanceDuration result = provider.getDistanceAndDuration(locA, locB);
    assertThat(result.durationSeconds()).isEqualTo(1001);
  }
}
