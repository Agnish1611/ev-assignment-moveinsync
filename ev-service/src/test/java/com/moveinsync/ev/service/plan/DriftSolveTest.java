package com.moveinsync.ev.service.plan;

import static org.assertj.core.api.Assertions.assertThat;

import com.moveinsync.ev.planner.input.PlannerInput;
import com.moveinsync.ev.service.api.JobClaims;
import com.moveinsync.ev.service.api.JobSettings;
import com.moveinsync.ev.service.bus.BusRecord;
import com.moveinsync.ev.service.bus.BusSettings;
import com.moveinsync.ev.service.bus.Delivery;
import com.moveinsync.ev.service.bus.Envelope;
import com.moveinsync.ev.service.bus.EnvelopeCodec;
import com.moveinsync.ev.service.bus.MemoryMessageBus;
import com.moveinsync.ev.service.bus.PlannerWorker;
import com.moveinsync.ev.service.bus.SeenSequences;
import com.moveinsync.ev.service.bus.Topics;
import com.moveinsync.ev.service.fleet.FleetLoad;
import com.moveinsync.ev.service.observe.DeliveryObserver;
import com.moveinsync.ev.service.repository.JobRepository;
import com.moveinsync.ev.service.repository.PlanHeadRepository;
import com.moveinsync.ev.service.repository.PlanReadRepository;
import com.moveinsync.ev.validator.PlanValidator;
import java.sql.SQLException;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

/** A drifted reading and a failed plug publish one validator-clean head. */
class DriftSolveTest {

  private static final Clock CLOCK = Clock.fixed(DriftFleet.START, ZoneOffset.UTC);

  @Test
  void aTwentyTwoPercentReadingMovesTheNextTrip() throws Exception {
    String tenant = "tenant-drift-battery";
    Worker worker = start(tenant, DriftFleet.fortyOne(tenant));
    String holder = cab(tenant, 1L, "TRIP-NEXT");
    DriftFleet.slack(tenant, holder);
    worker.listen();
    worker.send(reading(tenant, holder, "device", 7L, DriftFleet.DRIFTED));
    await(tenant, 2L);

    assertThat(holder).isNotBlank();
    assertThat(cab(tenant, 2L, "TRIP-NEXT")).isNotEqualTo(holder);
    String locked = cab(tenant, 1L, "TRIP-LOCKED");
    if (!holder.equals(locked)) {
      assertThat(cab(tenant, 2L, "TRIP-LOCKED")).isEqualTo(locked);
    }
    assertThat(changes(tenant)).isLessThanOrEqualTo(3L);
    assertThat(soc(tenant, holder)).isEqualTo(DriftFleet.DRIFTED);
    worker.close();
  }

  @Test
  void aDuplicateAndAnOlderTriggerDoNotPublishAgain() throws Exception {
    String tenant = "tenant-drift-order";
    Worker worker = start(tenant, DriftFleet.fortyOne(tenant));
    String holder = cab(tenant, 1L, "TRIP-NEXT");
    DriftFleet.slack(tenant, holder);
    Envelope first = trigger(tenant, holder, "device", 9L, DriftFleet.DRIFTED);
    worker.send(first);
    await(tenant, 2L);
    worker.send(first);
    worker.send(trigger(tenant, holder, "device", 8L, 10_000L));
    awaitQuiet(tenant);

    assertThat(version(tenant)).isEqualTo(2L);
    assertThat(soc(tenant, holder)).isEqualTo(DriftFleet.DRIFTED);
    worker.close();
  }

  @Test
  void aFailedPlugMovesOnlyItsSessions() throws Exception {
    String tenant = "tenant-drift-plug";
    Worker worker = start(tenant, DriftFleet.twoPlugs(tenant));
    String failed =
        PlanDatabase.text(
            "SELECT charger_id FROM charging_session WHERE tenant_id = ? AND shard = ?"
                + " AND version = 1 ORDER BY charger_id",
            tenant,
            DriftFleet.SHARD);
    String other =
        PlanDatabase.text(
            "SELECT charger_id FROM charging_session WHERE tenant_id = ? AND shard = ?"
                + " AND version = 1 AND charger_id <> ? ORDER BY charger_id",
            tenant,
            DriftFleet.SHARD,
            failed);
    long dependent =
        PlanDatabase.count(
            "SELECT count(*) FROM charging_session WHERE tenant_id = ? AND shard = ?"
                + " AND version = 1 AND charger_id = ?",
            tenant,
            DriftFleet.SHARD,
            failed);
    long kept = kept(tenant, other);
    worker.send(failure(tenant, failed, 4L));
    await(tenant, 2L);

    assertThat(failed).isNotBlank();
    assertThat(other).isNotBlank();
    assertThat(dependent).isPositive();
    assertThat(onPlug(tenant, 2L, failed)).isZero();
    assertThat(kept(tenant, other)).isEqualTo(kept);
    assertThat(tripChanges(tenant)).isZero();
    worker.close();
  }

  private static long kept(String tenant, String chargerId) {
    if (chargerId.isEmpty()) {
      return 0L;
    }
    return PlanDatabase.count(
        "SELECT count(*) FROM charging_session a WHERE a.tenant_id = ? AND a.shard = ?"
            + " AND a.version = 1 AND a.charger_id = ? AND EXISTS ("
            + " SELECT 1 FROM charging_session b WHERE b.tenant_id = a.tenant_id"
            + " AND b.shard = a.shard AND b.version = 2 AND b.vehicle_id = a.vehicle_id"
            + " AND b.charger_id = a.charger_id AND b.start_ts = a.start_ts"
            + " AND b.end_ts = a.end_ts)",
        tenant,
        DriftFleet.SHARD,
        chargerId);
  }

  private static long onPlug(String tenant, long version, String chargerId) {
    return PlanDatabase.count(
        "SELECT count(*) FROM charging_session WHERE tenant_id = ? AND shard = ?"
            + " AND version = ? AND charger_id = ?",
        tenant,
        DriftFleet.SHARD,
        version,
        chargerId);
  }

  private static long tripChanges(String tenant) {
    return PlanDatabase.count(
        "SELECT count(*) FROM route_stop a JOIN route_stop b"
            + " ON b.tenant_id = a.tenant_id AND b.shard = a.shard AND b.trip_id = a.trip_id"
            + " AND b.version = 2 WHERE a.tenant_id = ? AND a.shard = ? AND a.version = 1"
            + " AND a.vehicle_id <> b.vehicle_id",
        tenant,
        DriftFleet.SHARD);
  }

  private static long changes(String tenant) {
    return PlanDatabase.count(
        "SELECT count(*) FROM route_stop a LEFT JOIN route_stop b"
            + " ON b.tenant_id = a.tenant_id AND b.shard = a.shard AND b.trip_id = a.trip_id"
            + " AND b.version = 2 WHERE a.tenant_id = ? AND a.shard = ? AND a.version = 1"
            + " AND (b.vehicle_id IS NULL OR a.vehicle_id <> b.vehicle_id)",
        tenant,
        DriftFleet.SHARD);
  }

  private static String cab(String tenant, long version, String tripId) {
    return PlanDatabase.text(
        "SELECT vehicle_id FROM route_stop WHERE tenant_id = ? AND shard = ?"
            + " AND version = ? AND trip_id = ?",
        tenant,
        DriftFleet.SHARD,
        version,
        tripId);
  }

  private static long soc(String tenant, String cabId) {
    return PlanDatabase.count(
        "SELECT soc_wh FROM vehicle_state WHERE tenant_id = ? AND vehicle_id = ?", tenant, cabId);
  }

  private static long version(String tenant) {
    return PlanDatabase.count(
        "SELECT version FROM plan_head WHERE tenant_id = ? AND shard = ?",
        tenant,
        DriftFleet.SHARD);
  }

  private static void await(String tenant, long wanted) {
    long deadline = System.nanoTime() + 5_000_000_000L;
    while (System.nanoTime() < deadline && version(tenant) < wanted) {
      Thread.onSpinWait();
    }
    assertThat(version(tenant)).isEqualTo(wanted);
  }

  private static void awaitQuiet(String tenant) {
    long deadline = System.nanoTime() + 1_000_000_000L;
    while (System.nanoTime() < deadline) {
      Thread.onSpinWait();
    }
    assertThat(version(tenant)).isEqualTo(2L);
  }

  private static Envelope reading(
      String tenant, String cabId, String producer, long sequence, long socWh) {
    return new Envelope(
        tenant + ":" + producer + ":" + sequence,
        tenant,
        producer,
        sequence,
        DriftFleet.START,
        Envelope.SCHEMA_VERSION,
        new Envelope.Telemetry(cabId, socWh));
  }

  private static Envelope trigger(
      String tenant, String cabId, String producer, long sequence, long socWh) {
    return new Envelope(
        tenant + ":" + producer + ":" + sequence,
        tenant,
        producer,
        sequence,
        DriftFleet.START,
        Envelope.SCHEMA_VERSION,
        new Envelope.PlanTrigger(
            "BATTERY", cabId, DriftFleet.SHARD, DriftFleet.START, OptionalLong.of(socWh)));
  }

  private static Envelope failure(String tenant, String chargerId, long sequence) {
    return new Envelope(
        tenant + ":device:" + sequence,
        tenant,
        "device",
        sequence,
        DriftFleet.START,
        Envelope.SCHEMA_VERSION,
        new Envelope.PlanTrigger(
            "CHARGER_FAILURE",
            chargerId,
            DriftFleet.SHARD,
            DriftFleet.START,
            OptionalLong.empty()));
  }

  private static Worker start(String tenant, PlannerInput input) throws SQLException {
    DriftFleet.publish(PlanDatabase.open(), input);
    MemoryMessageBus bus = new MemoryMessageBus();
    JobRepository jobs = new JobRepository();
    JobSolve solve =
        new JobSolve(
            PlanDatabase.open(),
            jobs,
            new JobClaims(jobs, new JobSettings(120, 5_000)),
            new FleetLoad(PlanDatabase.open()),
            new PlanPublication(
                new PlanValidator(), new PlanHeadRepository(), new PlanReadRepository(), CLOCK),
            new PlanReadRepository(),
            new PlanHeadRepository(),
            CLOCK,
            BusSettings.standard().longestSolveMillis(),
            DeliveryObserver.none());
    Delivery delivery =
        new Delivery(
            bus, new SeenSequences(), CLOCK, BusSettings.standard(), DeliveryObserver.none());
    PlannerWorker planner = new PlannerWorker(bus, delivery, BusSettings.standard());
    planner.start(solve::accept);
    FleetDetector detector =
        new FleetDetector(PlanDatabase.open(), bus, CLOCK, DeliveryObserver.none());
    return new Worker(tenant, bus, planner, detector);
  }

  private record Worker(
      String tenant, MemoryMessageBus bus, PlannerWorker planner, FleetDetector detector) {

    void listen() {
      detector.start();
    }

    void send(Envelope envelope) {
      String topic =
          envelope.payload() instanceof Envelope.Telemetry
              ? Topics.TELEMETRY
              : Topics.PLAN_TRIGGERS;
      bus.send(BusRecord.of(topic, tenant + ":" + DriftFleet.SHARD, EnvelopeCodec.write(envelope)));
    }

    void close() {
      planner.close();
    }
  }
}
