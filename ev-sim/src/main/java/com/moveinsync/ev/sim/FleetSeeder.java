package com.moveinsync.ev.sim;

import com.moveinsync.ev.domain.trip.Trip;
import com.moveinsync.ev.planner.input.PlannerInput;

/**
 * JSON body for {@code POST /v1/admin/trips}. The evening fleet is 1,500 stored trips plus one row
 * whose finish is before its start, so a batch can quarantine that row without failing the rest.
 */
public final class FleetSeeder {

  public static final String SHARD = "shard-scale";
  public static final String QUARANTINE_ID = "TRIP-BAD";

  private FleetSeeder() {
    throw new IllegalStateException("utility");
  }

  public static String eveningTrips() {
    PlannerInput fleet = ScaleScenario.build();
    StringBuilder body = new StringBuilder();
    body.append('[');
    boolean first = true;
    for (Trip trip : fleet.trips()) {
      if (!first) {
        body.append(',');
      }
      first = false;
      appendTrip(body, trip);
    }
    body.append(',');
    appendQuarantine(body);
    body.append(']');
    return body.toString();
  }

  private static void appendQuarantine(StringBuilder body) {
    body.append('{');
    field(body, "tripId", QUARANTINE_ID);
    body.append(',');
    field(body, "start", "2026-10-30T08:10:00Z");
    body.append(',');
    field(body, "fromLocation", "LOC-P");
    body.append(',');
    field(body, "toLocation", "LOC-D");
    body.append(',');
    body.append("\"distanceMetres\":2000");
    body.append(',');
    field(body, "latestFinish", "2026-10-30T08:09:59Z");
    body.append(',');
    field(body, "employeeRef", "EMP-0");
    body.append(',');
    field(body, "shardId", SHARD);
    body.append(',');
    body.append("\"durationSeconds\":600");
    body.append(',');
    body.append("\"fromLatitudeMicros\":0,\"fromLongitudeMicros\":0,");
    body.append("\"toLatitudeMicros\":0,\"toLongitudeMicros\":0,");
    field(body, "horizonStart", ScaleScenario.START.toString());
    body.append(',');
    field(body, "horizonEnd", ScaleScenario.END.toString());
    body.append('}');
  }

  private static void appendTrip(StringBuilder body, Trip trip) {
    body.append('{');
    field(body, "tripId", trip.id().value());
    body.append(',');
    field(body, "start", trip.startTs().toString());
    body.append(',');
    field(body, "fromLocation", trip.fromLocation().id().value());
    body.append(',');
    field(body, "toLocation", trip.toLocation().id().value());
    body.append(',');
    body.append("\"distanceMetres\":").append(trip.distanceMeters());
    body.append(',');
    field(body, "latestFinish", trip.latestFinishTs().toString());
    body.append(',');
    field(body, "employeeRef", trip.employeeRef());
    body.append(',');
    field(body, "shardId", SHARD);
    body.append(',');
    body.append("\"durationSeconds\":").append(trip.estimatedDurationSeconds());
    body.append(',');
    body.append("\"fromLatitudeMicros\":").append(trip.fromLocation().latitudeMicros());
    body.append(',');
    body.append("\"fromLongitudeMicros\":").append(trip.fromLocation().longitudeMicros());
    body.append(',');
    body.append("\"toLatitudeMicros\":").append(trip.toLocation().latitudeMicros());
    body.append(',');
    body.append("\"toLongitudeMicros\":").append(trip.toLocation().longitudeMicros());
    body.append(',');
    field(body, "horizonStart", ScaleScenario.START.toString());
    body.append(',');
    field(body, "horizonEnd", ScaleScenario.END.toString());
    body.append('}');
  }

  private static void field(StringBuilder body, String name, String value) {
    body.append('"').append(name).append("\":\"").append(value).append('"');
  }
}
