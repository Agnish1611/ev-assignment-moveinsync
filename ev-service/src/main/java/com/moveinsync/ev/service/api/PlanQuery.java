package com.moveinsync.ev.service.api;

import com.moveinsync.ev.service.api.ApiModels.OwnTrips;
import com.moveinsync.ev.service.api.ApiModels.PlanDiffView;
import com.moveinsync.ev.service.api.ApiModels.PlanHeadView;
import com.moveinsync.ev.service.api.ApiModels.PlanVersionView;
import com.moveinsync.ev.service.api.ApiModels.TimelineView;
import com.moveinsync.ev.service.api.ApiModels.UnassignedView;
import com.moveinsync.ev.service.cache.PlanReadCache;
import com.moveinsync.ev.service.repository.PlanViewRepository;
import java.util.List;
import java.util.Objects;
import java.util.OptionalLong;

/** Reads published plans for the token tenant. A cached version is immutable. */
public final class PlanQuery {

  private final AppConnections connections;
  private final PlanViewRepository views;
  private final PlanReadCache cache;
  private final int maxIdLength;

  public PlanQuery(
      AppConnections connections, PlanViewRepository views, PlanReadCache cache, int maxIdLength) {
    this.connections = connections;
    this.views = views;
    this.cache = Objects.requireNonNull(cache, "cache must not be null");
    this.maxIdLength = maxIdLength;
  }

  public PlanHeadView current(Caller caller, String shard) {
    String safeShard = OpaqueIds.require(shard, maxIdLength);
    long version = cache.current(caller.tenant(), safeShard, () -> loadCurrent(caller, safeShard));
    return new PlanHeadView(safeShard, version);
  }

  public PlanVersionView version(Caller caller, String shard, long version) {
    String safeShard = shard(shard, version);
    return cache.version(
        caller.tenant(), safeShard, version, () -> loadVersion(caller, safeShard, version));
  }

  public String validationReport(Caller caller, String shard, long version) {
    String safeShard = shard(shard, version);
    return cache.report(
        caller.tenant(), safeShard, version, () -> loadReport(caller, safeShard, version));
  }

  public PlanDiffView diff(Caller caller, String shard, long version, long from) {
    String safeShard = shard(shard, version);
    if (from < 0L) {
      throw new ApiException(400, "INVALID_VERSION", "The plan version must be positive");
    }
    return connections.read(
        caller.tenant(),
        connection ->
            views
                .diff(connection, caller.tenant(), safeShard, version, from)
                .orElseThrow(PlanQuery::missingDiff));
  }

  public List<UnassignedView> unassigned(Caller caller, String shard, long version) {
    String safeShard = shard(shard, version);
    return cache.unassigned(
        caller.tenant(), safeShard, version, () -> loadUnassigned(caller, safeShard, version));
  }

  public TimelineView timeline(Caller caller, String cabId, String shard, Long version) {
    String cab = OpaqueIds.require(cabId, maxIdLength);
    String safeShard = OpaqueIds.require(shard, maxIdLength);
    long resolved = resolveVersion(caller, safeShard, version);
    return cache.timeline(
        caller.tenant(),
        safeShard,
        resolved,
        cab,
        () -> loadTimeline(caller, safeShard, resolved, cab));
  }

  public OwnTrips ownTrips(Caller caller) {
    return connections.read(
        caller.tenant(),
        connection -> new OwnTrips(views.ownTrips(connection, caller.tenant(), caller.userId())));
  }

  private long loadCurrent(Caller caller, String shard) {
    return connections.read(
        caller.tenant(),
        connection -> {
          OptionalLong version = views.currentVersion(connection, caller.tenant(), shard);
          if (!version.isPresent()) {
            throw missing();
          }
          return version.orElseThrow();
        });
  }

  private PlanVersionView loadVersion(Caller caller, String shard, long version) {
    return connections.read(
        caller.tenant(),
        connection ->
            views
                .version(connection, caller.tenant(), shard, version)
                .orElseThrow(PlanQuery::missing));
  }

  private String loadReport(Caller caller, String shard, long version) {
    return connections.read(
        caller.tenant(),
        connection ->
            views
                .validationReport(connection, caller.tenant(), shard, version)
                .orElseThrow(PlanQuery::missing));
  }

  private List<UnassignedView> loadUnassigned(Caller caller, String shard, long version) {
    return connections.read(
        caller.tenant(),
        connection -> {
          if (views.version(connection, caller.tenant(), shard, version).isEmpty()) {
            throw missing();
          }
          return views.unassigned(connection, caller.tenant(), shard, version);
        });
  }

  private TimelineView loadTimeline(Caller caller, String shard, long version, String cab) {
    return connections.read(
        caller.tenant(),
        connection -> {
          if (views.version(connection, caller.tenant(), shard, version).isEmpty()) {
            throw missing();
          }
          return new TimelineView(
              cab,
              version,
              views.stops(connection, caller.tenant(), shard, version, cab),
              views.sessions(connection, caller.tenant(), shard, version, cab));
        });
  }

  private long resolveVersion(Caller caller, String shard, Long version) {
    if (version == null) {
      return cache.current(caller.tenant(), shard, () -> loadCurrent(caller, shard));
    }
    if (version <= 0L) {
      throw new ApiException(400, "INVALID_VERSION", "The plan version must be positive");
    }
    return version;
  }

  private String shard(String shard, long version) {
    if (version <= 0L) {
      throw new ApiException(400, "INVALID_VERSION", "The plan version must be positive");
    }
    return OpaqueIds.require(shard, maxIdLength);
  }

  private static ApiException missing() {
    return new ApiException(404, "PLAN_NOT_FOUND", "No plan is published");
  }

  private static ApiException missingDiff() {
    return new ApiException(404, "DIFF_NOT_FOUND", "No stored diff matches that version");
  }
}
