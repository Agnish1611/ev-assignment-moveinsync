package com.moveinsync.ev.service.fleet;

/** Reads one shard's planner input and the validator's ground truth. */
@FunctionalInterface
public interface FleetSource {

  LoadedFleet load(String tenant, String shard);
}
