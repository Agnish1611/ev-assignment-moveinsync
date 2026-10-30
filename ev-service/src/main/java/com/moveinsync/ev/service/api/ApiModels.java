package com.moveinsync.ev.service.api;

import java.util.List;
import java.util.Objects;

/** Request and response bodies. Absent text is an empty string so the JSON has no nulls. */
public final class ApiModels {

  private ApiModels() {
    throw new IllegalStateException("utility");
  }

  public record PlanJobRequest(String shard) {}

  public record PlanJobAccepted(String jobId, String shard, String status) {}

  public record PlanHeadView(String shard, long version) {}

  public record PlanVersionView(
      String shard, long version, Long parentVersion, String planId, String createdAt) {}

  public record PlanDiffView(String shard, long version, long from, String changes) {}

  public record UnassignedView(String tripId, String reasonCode, String explanation) {}

  public record StopView(String tripId, String start, String end, long arrivalSocWh) {}

  public record SessionView(
      String sessionId,
      String chargerId,
      String start,
      String end,
      long startSocWh,
      long targetSocWh) {}

  public record TimelineView(
      String cabId, long version, List<StopView> stops, List<SessionView> sessions) {
    public TimelineView {
      Objects.requireNonNull(stops, "stops must not be null");
      Objects.requireNonNull(sessions, "sessions must not be null");
      stops = List.copyOf(stops);
      sessions = List.copyOf(sessions);
    }
  }

  public record OwnTrip(String tripId, String status, String cabId, String eta) {}

  public record OwnTrips(List<OwnTrip> trips) {
    public OwnTrips {
      Objects.requireNonNull(trips, "trips must not be null");
      trips = List.copyOf(trips);
    }
  }

  public record BatteryRequest(String cabId, long socWh, String observedAt, long sequence) {}

  public record ChargerFailRequest(String hubId, String shard, long sequence) {}

  public record ReplanRequest(String shard, String kind, String subjectId, long sequence) {}

  public record Accepted(String status) {}

  public record HubRequest(
      String hubId, String name, String locationId, String zone, int chargerCount) {}

  public record ChargerRequest(String chargerId, String hubId, int powerKw) {}

  public record VehicleRequest(
      String vehicleId, long batteryWh, int consumptionWhPerKm, int maxChargeKw) {}

  public record TripRequest(
      String tripId,
      String start,
      String fromLocation,
      String toLocation,
      int distanceMetres,
      String latestFinish,
      String employeeRef) {}

  public record TariffRequest(
      String hubId, String windowId, String localStart, String localEnd, long rateMicros) {}

  public record UserRequest(String userId, String role) {}

  public record Created(String id) {}
}
