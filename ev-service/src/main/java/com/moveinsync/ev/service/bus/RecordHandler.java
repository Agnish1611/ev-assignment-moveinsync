package com.moveinsync.ev.service.bus;

/** Handles one record. {@code ack} commits the offset and must run after the work is durable. */
@FunctionalInterface
public interface RecordHandler {

  void onRecord(BusRecord record, Runnable ack);
}
