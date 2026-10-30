package com.moveinsync.ev.service.plan;

import com.moveinsync.ev.domain.hub.ChargerId;
import com.moveinsync.ev.domain.hub.HubId;
import com.moveinsync.ev.domain.location.Location;
import com.moveinsync.ev.domain.location.LocationId;
import com.moveinsync.ev.domain.schedule.ChargingSession;
import com.moveinsync.ev.domain.schedule.FleetPlan;
import com.moveinsync.ev.domain.schedule.RouteActivity;
import com.moveinsync.ev.domain.schedule.TripAssignment;
import com.moveinsync.ev.domain.schedule.UnassignedReasonCode;
import com.moveinsync.ev.domain.schedule.UnassignedTrip;
import com.moveinsync.ev.domain.schedule.VehicleSchedule;
import com.moveinsync.ev.domain.tenant.TenantId;
import com.moveinsync.ev.domain.trip.Trip;
import com.moveinsync.ev.domain.trip.TripId;
import com.moveinsync.ev.domain.vehicle.Vehicle;
import com.moveinsync.ev.domain.vehicle.VehicleId;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/** Gzipped binary image of a fleet plan. The bytes are the snapshot, not a log line. */
final class PlanDocuments {

  private static final int FORMAT = 1;
  private static final int TRIP = 1;
  private static final int CHARGE = 2;

  private PlanDocuments() {
    throw new IllegalStateException("utility");
  }

  static byte[] gzip(FleetPlan plan) {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (GZIPOutputStream gzip = new GZIPOutputStream(bytes);
        DataOutputStream out = new DataOutputStream(gzip)) {
      writePlan(out, plan);
    } catch (IOException ex) {
      throw new IllegalArgumentException("plan could not be stored", ex);
    }
    return bytes.toByteArray();
  }

  static FleetPlan gunzip(byte[] compressed) {
    try (DataInputStream in =
        new DataInputStream(new GZIPInputStream(new ByteArrayInputStream(compressed)))) {
      return readPlan(in);
    } catch (IOException ex) {
      throw new IllegalArgumentException("plan snapshot could not be read", ex);
    }
  }

  private static void writePlan(DataOutputStream out, FleetPlan plan) throws IOException {
    out.writeInt(FORMAT);
    out.writeUTF(plan.planId());
    out.writeUTF(plan.tenantId());
    writeInstant(out, plan.horizonStart());
    writeInstant(out, plan.horizonEnd());
    out.writeLong(plan.seed());
    out.writeUTF(plan.inputHash());
    out.writeInt(plan.vehicleSchedules().size());
    for (VehicleSchedule schedule : plan.vehicleSchedules()) {
      writeSchedule(out, plan.tenantId(), schedule);
    }
    out.writeInt(plan.unassignedTrips().size());
    for (UnassignedTrip unassigned : plan.unassignedTrips()) {
      writeTrip(out, plan.tenantId(), unassigned.trip());
      out.writeUTF(unassigned.reasonCode().name());
      out.writeUTF(unassigned.explanation());
    }
  }

  private static void writeSchedule(DataOutputStream out, String tenant, VehicleSchedule schedule)
      throws IOException {
    Vehicle vehicle = schedule.vehicle();
    if (!vehicle.tenantId().value().equals(tenant)) {
      throw new IllegalArgumentException("schedule tenant does not match the plan");
    }
    out.writeUTF(vehicle.id().value());
    out.writeLong(vehicle.batteryCapacityWh());
    out.writeLong(vehicle.baseConsumptionWhPerKm());
    out.writeInt(vehicle.maxChargeKw());
    writeLocation(out, schedule.initialLocation());
    out.writeLong(schedule.initialSocWh());
    writeInstant(out, schedule.availableFrom());
    out.writeInt(schedule.activities().size());
    for (RouteActivity activity : schedule.activities()) {
      writeActivity(out, tenant, vehicle.id().value(), activity);
    }
  }

  private static void writeActivity(
      DataOutputStream out, String tenant, String vehicleId, RouteActivity activity)
      throws IOException {
    if (activity instanceof TripAssignment trip) {
      if (!trip.vehicleId().value().equals(vehicleId)) {
        throw new IllegalArgumentException("trip assignment cab does not match the schedule");
      }
      out.writeByte(TRIP);
      writeTrip(out, tenant, trip.trip());
      writeInstant(out, trip.pickupTime());
      writeInstant(out, trip.dropoffTime());
      out.writeInt(trip.deadheadDistanceMeters());
      out.writeInt(trip.deadheadDurationSeconds());
      out.writeLong(trip.deadheadEnergyWh());
      out.writeLong(trip.arrivalAtPickupSocWh());
      out.writeLong(trip.departureAfterDropoffSocWh());
      return;
    }
    if (activity instanceof ChargingSession session) {
      if (!session.vehicleId().value().equals(vehicleId)) {
        throw new IllegalArgumentException("charging session cab does not match the schedule");
      }
      out.writeByte(CHARGE);
      out.writeUTF(session.sessionId());
      out.writeUTF(session.hubId().value());
      out.writeUTF(session.chargerId().value());
      writeInstant(out, session.startTime());
      writeInstant(out, session.endTime());
      out.writeLong(session.startSocWh());
      out.writeLong(session.targetSocWh());
      out.writeLong(session.costMicros());
      return;
    }
    throw new IllegalArgumentException("activity cannot be stored");
  }

  private static FleetPlan readPlan(DataInputStream in) throws IOException {
    int format = in.readInt();
    if (format != FORMAT) {
      throw new IllegalArgumentException("plan snapshot format is not supported");
    }
    String planId = in.readUTF();
    String tenant = in.readUTF();
    Instant start = readInstant(in);
    Instant end = readInstant(in);
    long seed = in.readLong();
    String hash = in.readUTF();
    int schedules = in.readInt();
    List<VehicleSchedule> vehicles = new ArrayList<>();
    for (int index = 0; index < schedules; index++) {
      vehicles.add(readSchedule(in, tenant));
    }
    int parked = in.readInt();
    List<UnassignedTrip> unassigned = new ArrayList<>();
    for (int index = 0; index < parked; index++) {
      Trip trip = readTrip(in, tenant);
      unassigned.add(
          UnassignedTrip.of(trip, UnassignedReasonCode.valueOf(in.readUTF()), in.readUTF()));
    }
    return new FleetPlan(planId, tenant, start, end, vehicles, unassigned, seed, hash);
  }

  private static VehicleSchedule readSchedule(DataInputStream in, String tenant)
      throws IOException {
    Vehicle vehicle =
        new Vehicle(
            TenantId.of(tenant),
            VehicleId.of(in.readUTF()),
            in.readLong(),
            in.readLong(),
            in.readInt());
    Location location = readLocation(in);
    long soc = in.readLong();
    Instant available = readInstant(in);
    int count = in.readInt();
    List<RouteActivity> activities = new ArrayList<>();
    for (int index = 0; index < count; index++) {
      activities.add(readActivity(in, tenant, vehicle.id().value()));
    }
    return new VehicleSchedule(vehicle, location, soc, available, activities);
  }

  private static RouteActivity readActivity(DataInputStream in, String tenant, String vehicleId)
      throws IOException {
    int kind = in.readUnsignedByte();
    if (kind == TRIP) {
      return readAssignment(in, tenant, vehicleId);
    }
    if (kind == CHARGE) {
      return readSession(in, vehicleId);
    }
    throw new IllegalArgumentException("activity cannot be stored");
  }

  private static TripAssignment readAssignment(DataInputStream in, String tenant, String vehicleId)
      throws IOException {
    Trip trip = readTrip(in, tenant);
    return new TripAssignment(
        trip,
        VehicleId.of(vehicleId),
        readInstant(in),
        readInstant(in),
        in.readInt(),
        in.readInt(),
        in.readLong(),
        in.readLong(),
        in.readLong());
  }

  private static ChargingSession readSession(DataInputStream in, String vehicleId)
      throws IOException {
    return new ChargingSession(
        in.readUTF(),
        VehicleId.of(vehicleId),
        HubId.of(in.readUTF()),
        ChargerId.of(in.readUTF()),
        readInstant(in),
        readInstant(in),
        in.readLong(),
        in.readLong(),
        in.readLong());
  }

  private static void writeTrip(DataOutputStream out, String tenant, Trip trip) throws IOException {
    if (!trip.tenantId().value().equals(tenant)) {
      throw new IllegalArgumentException("trip tenant does not match the plan");
    }
    out.writeUTF(trip.id().value());
    writeInstant(out, trip.startTs());
    writeLocation(out, trip.fromLocation());
    writeLocation(out, trip.toLocation());
    out.writeInt(trip.distanceMeters());
    out.writeInt(trip.estimatedDurationSeconds());
    writeInstant(out, trip.latestFinishTs());
    out.writeUTF(trip.employeeRef());
  }

  private static Trip readTrip(DataInputStream in, String tenant) throws IOException {
    return new Trip(
        TenantId.of(tenant),
        TripId.of(in.readUTF()),
        readInstant(in),
        readLocation(in),
        readLocation(in),
        in.readInt(),
        in.readInt(),
        readInstant(in),
        in.readUTF());
  }

  private static void writeLocation(DataOutputStream out, Location location) throws IOException {
    out.writeUTF(location.id().value());
    out.writeUTF(location.name());
    out.writeInt(location.latitudeMicros());
    out.writeInt(location.longitudeMicros());
  }

  private static Location readLocation(DataInputStream in) throws IOException {
    return new Location(LocationId.of(in.readUTF()), in.readUTF(), in.readInt(), in.readInt());
  }

  private static void writeInstant(DataOutputStream out, Instant instant) throws IOException {
    out.writeLong(instant.getEpochSecond());
    out.writeInt(instant.getNano());
  }

  private static Instant readInstant(DataInputStream in) throws IOException {
    return Instant.ofEpochSecond(in.readLong(), in.readInt());
  }
}
