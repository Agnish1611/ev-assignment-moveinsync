package com.moveinsync.ev.service.cache;

import com.moveinsync.ev.service.api.ApiModels.PlanVersionView;
import com.moveinsync.ev.service.api.ApiModels.TimelineView;
import com.moveinsync.ev.service.api.ApiModels.UnassignedView;
import java.util.List;
import java.util.function.Supplier;

/** Read-through cache. A published version is kept until it falls out of the size bound. */
public interface PlanReadCache {

  long current(String tenant, String shard, Supplier<Long> load);

  PlanVersionView version(
      String tenant, String shard, long version, Supplier<PlanVersionView> load);

  String report(String tenant, String shard, long version, Supplier<String> load);

  List<UnassignedView> unassigned(
      String tenant, String shard, long version, Supplier<List<UnassignedView>> load);

  TimelineView timeline(
      String tenant, String shard, long version, String cabId, Supplier<TimelineView> load);

  String fleet(String tenant, String kind, String id, Supplier<String> load);

  void evictCurrent(String tenant, String shard);

  void evictFleet(String tenant);
}
