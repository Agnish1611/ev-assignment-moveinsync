package com.moveinsync.ev.domain.distance;

import com.moveinsync.ev.domain.location.Location;
import com.moveinsync.ev.domain.location.LocationId;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Pre-computed distance matrix implementation of {@link DistanceProvider}. */
public final class MatrixDistanceProvider implements DistanceProvider {

  public record LocationPair(LocationId from, LocationId to) {
    public LocationPair {
      Objects.requireNonNull(from, "from LocationId must not be null");
      Objects.requireNonNull(to, "to LocationId must not be null");
    }
  }

  private final Map<LocationPair, DistanceDuration> matrix;
  private final DistanceProvider fallbackProvider;

  public MatrixDistanceProvider(Map<LocationPair, DistanceDuration> matrix) {
    this(matrix, null);
  }

  public MatrixDistanceProvider(
      Map<LocationPair, DistanceDuration> matrix, DistanceProvider fallbackProvider) {
    Objects.requireNonNull(matrix, "matrix map must not be null");
    this.matrix = Collections.unmodifiableMap(new HashMap<>(matrix));
    this.fallbackProvider = fallbackProvider;
  }

  @Override
  public DistanceDuration getDistanceAndDuration(Location from, Location to) {
    return findDistanceAndDuration(from, to)
        .orElseThrow(
            () ->
                new IllegalArgumentException(
                    "No distance entry for route from " + from.id() + " to " + to.id()));
  }

  @Override
  public Optional<DistanceDuration> findDistanceAndDuration(Location from, Location to) {
    Objects.requireNonNull(from, "from location must not be null");
    Objects.requireNonNull(to, "to location must not be null");

    if (from.id().equals(to.id())) {
      return Optional.of(DistanceDuration.zero());
    }

    LocationPair pair = new LocationPair(from.id(), to.id());
    DistanceDuration direct = matrix.get(pair);
    if (direct != null) {
      return Optional.of(direct);
    }

    if (fallbackProvider != null) {
      return fallbackProvider.findDistanceAndDuration(from, to);
    }

    return Optional.empty();
  }
}
