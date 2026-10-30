package com.moveinsync.ev.service.api;

import com.moveinsync.ev.service.api.ApiModels.OwnTrips;
import com.moveinsync.ev.service.api.ApiModels.PlanDiffView;
import com.moveinsync.ev.service.api.ApiModels.PlanHeadView;
import com.moveinsync.ev.service.api.ApiModels.PlanVersionView;
import com.moveinsync.ev.service.api.ApiModels.TimelineView;
import com.moveinsync.ev.service.api.ApiModels.UnassignedView;
import com.moveinsync.ev.service.repository.PlanViewRepository;
import java.util.List;
import java.util.OptionalLong;

/** Reads published plans for the token tenant. */
public final class PlanQuery {

  private final AppConnections connections;
  private final PlanViewRepository views;
  private final int maxIdLength;

  public PlanQuery(AppConnections connections, PlanViewRepository views, int maxIdLength) {
    this.connections = connections;
    this.views = views;
    this.maxIdLength = maxIdLength;
  }

  public PlanHeadView current(Caller caller, String shard) {
    String safeShard = OpaqueIds.require(shard, maxIdLength);
    return connections.read(
        caller.tenant(),
        connection -> {
          OptionalLong version = views.currentVersion(connection, caller.tenant(), safeShard);
          if (!version.isPresent()) {
            throw missing();
          }
          return new PlanHeadView(safeShard, version.orElseThrow());
        });
  }

  public PlanVersionView version(Caller caller, String shard, long version) {
    String safeShard = shard(shard, version);
    return connections.read(
        caller.tenant(),
        connection ->
            views
                .version(connection, caller.tenant(), safeShard, version)
                .orElseThrow(PlanQuery::missing));
  }

  public String validationReport(Caller caller, String shard, long version) {
    String safeShard = shard(shard, version);
    return connections.read(
        caller.tenant(),
        connection ->
            views
                .validationReport(connection, caller.tenant(), safeShard, version)
                .orElseThrow(PlanQuery::missing));
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
    return connections.read(
        caller.tenant(),
        connection -> {
          if (views.version(connection, caller.tenant(), safeShard, version).isEmpty()) {
            throw missing();
          }
          return views.unassigned(connection, caller.tenant(), safeShard, version);
        });
  }

  public TimelineView timeline(Caller caller, String cabId, String shard, Long version) {
    String cab = OpaqueIds.require(cabId, maxIdLength);
    String safeShard = OpaqueIds.require(shard, maxIdLength);
    return connections.read(
        caller.tenant(),
        connection -> {
          long resolved = resolve(connection, caller.tenant(), safeShard, version);
          return new TimelineView(
              cab,
              resolved,
              views.stops(connection, caller.tenant(), safeShard, resolved, cab),
              views.sessions(connection, caller.tenant(), safeShard, resolved, cab));
        });
  }

  public OwnTrips ownTrips(Caller caller) {
    return connections.read(
        caller.tenant(),
        connection -> new OwnTrips(views.ownTrips(connection, caller.tenant(), caller.userId())));
  }

  private long resolve(java.sql.Connection connection, String tenant, String shard, Long version)
      throws java.sql.SQLException {
    if (version == null) {
      OptionalLong current = views.currentVersion(connection, tenant, shard);
      if (!current.isPresent()) {
        throw missing();
      }
      return current.orElseThrow();
    }
    if (version <= 0L) {
      throw new ApiException(400, "INVALID_VERSION", "The plan version must be positive");
    }
    if (views.version(connection, tenant, shard, version).isEmpty()) {
      throw missing();
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
