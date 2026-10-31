package com.moveinsync.ev.service.plan;

import com.moveinsync.ev.domain.schedule.FleetPlan;
import com.moveinsync.ev.domain.schedule.VehicleSchedule;
import com.moveinsync.ev.planner.solve.DriftEvent;
import com.moveinsync.ev.planner.telemetry.TelemetryGate;
import com.moveinsync.ev.planner.telemetry.TelemetryPolicy;
import com.moveinsync.ev.service.bus.BusRecord;
import com.moveinsync.ev.service.bus.BusSettings;
import com.moveinsync.ev.service.bus.Delivery;
import com.moveinsync.ev.service.bus.Envelope;
import com.moveinsync.ev.service.bus.EnvelopeCodec;
import com.moveinsync.ev.service.bus.MessageBus;
import com.moveinsync.ev.service.bus.SeenSequences;
import com.moveinsync.ev.service.bus.Topics;
import com.moveinsync.ev.service.fleet.FleetState;
import com.moveinsync.ev.service.observe.DeliveryObserver;
import com.moveinsync.ev.service.repository.PlanHeadRepository;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.ConcurrentHashMap;
import javax.sql.DataSource;

/**
 * Persists telemetry and charger status, then publishes a plan trigger when a reading has drifted
 * from the published head. The poll thread does not solve.
 */
final class FleetDetector {

  private final DataSource dataSource;
  private final MessageBus bus;
  private final FleetState state;
  private final Delivery readings;
  private final Delivery chargers;
  private final Map<String, TelemetryGate> gates = new ConcurrentHashMap<>();

  FleetDetector(DataSource dataSource, MessageBus bus, Clock clock, DeliveryObserver observer) {
    this.dataSource = dataSource;
    this.bus = bus;
    this.state = new FleetState();
    BusSettings settings = BusSettings.standard();
    this.readings = new Delivery(bus, new SeenSequences(), clock, settings, observer);
    this.chargers = new Delivery(bus, new SeenSequences(), clock, settings, observer);
  }

  public void start() {
    readings.install(Topics.TELEMETRY);
    chargers.install(Topics.CHARGER_STATUS);
    bus.subscribe(Topics.TELEMETRY, (record, ack) -> accept(readings, record, ack, this::reading));
    bus.subscribe(
        Topics.CHARGER_STATUS, (record, ack) -> accept(chargers, record, ack, this::charger));
  }

  private static void accept(
      Delivery delivery, BusRecord record, Runnable ack, Delivery.EnvelopeSink sink) {
    delivery.attempt(record, sink);
    ack.run();
  }

  private void reading(Envelope envelope) {
    if (!(envelope.payload() instanceof Envelope.Telemetry reading)) {
      return;
    }
    write(
        envelope.tenant(),
        connection ->
            state.reading(
                connection,
                envelope.tenant(),
                reading.cabId(),
                reading.socWh(),
                envelope.eventTime(),
                envelope.sequence()));
    String shard = shard(envelope.tenant(), reading.cabId());
    if (shard.isEmpty()) {
      return;
    }
    OptionalLong expected = expected(envelope.tenant(), shard, reading.cabId());
    gate(envelope.tenant())
        .observe(reading.cabId(), reading.socWh(), expected, envelope.eventTime())
        .ifPresent(event -> send(envelope, shard, event));
  }

  private void charger(Envelope envelope) {
    if (!(envelope.payload() instanceof Envelope.ChargerStatus status)) {
      return;
    }
    write(
        envelope.tenant(),
        connection ->
            state.status(connection, envelope.tenant(), status.chargerId(), status.operational()));
  }

  private void send(Envelope source, String shard, DriftEvent event) {
    Envelope envelope =
        new Envelope(
            source.tenant() + ":" + source.producer() + ":" + source.sequence(),
            source.tenant(),
            source.producer(),
            source.sequence(),
            event.observedAt(),
            Envelope.SCHEMA_VERSION,
            new Envelope.PlanTrigger(
                event.kind().name(),
                event.subjectId(),
                shard,
                event.observedAt(),
                event.reportedSocWh()));
    bus.send(
        BusRecord.of(
            Topics.PLAN_TRIGGERS, source.tenant() + ":" + shard, EnvelopeCodec.write(envelope)));
  }

  private TelemetryGate gate(String tenant) {
    return gates.computeIfAbsent(tenant, key -> new TelemetryGate(TelemetryPolicy.standard()));
  }

  private OptionalLong expected(String tenant, String shard, String cabId) {
    long version = head(tenant, shard);
    if (version < 0L) {
      return OptionalLong.empty();
    }
    byte[] image = image(tenant, shard, version);
    if (image.length == 0) {
      return OptionalLong.empty();
    }
    return soc(PlanDocuments.gunzip(image), cabId);
  }

  private static OptionalLong soc(FleetPlan plan, String cabId) {
    for (VehicleSchedule schedule : plan.vehicleSchedules()) {
      if (schedule.vehicleId().value().equals(cabId)) {
        return OptionalLong.of(schedule.initialSocWh());
      }
    }
    return OptionalLong.empty();
  }

  private String shard(String tenant, String cabId) {
    return read(tenant, connection -> state.shardOf(connection, tenant, cabId));
  }

  private long head(String tenant, String shard) {
    return read(
        tenant,
        connection -> {
          OptionalLong version = new PlanHeadRepository().headVersion(connection, tenant, shard);
          if (version.isEmpty()) {
            return -1L;
          }
          return version.orElseThrow();
        });
  }

  private byte[] image(String tenant, String shard, long version) {
    return read(tenant, connection -> state.headImage(connection, tenant, shard, version));
  }

  private void write(String tenant, SqlWork work) {
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      WorkerSession.apply(connection, tenant);
      work.run(connection);
      connection.commit();
    } catch (SQLException ex) {
      throw new PlanStoreException("job solve failed", ex);
    }
  }

  private <T> T read(String tenant, SqlRead<T> work) {
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      WorkerSession.apply(connection, tenant);
      T value = work.run(connection);
      connection.rollback();
      return value;
    } catch (SQLException ex) {
      throw new PlanStoreException("job solve failed", ex);
    }
  }

  @FunctionalInterface
  private interface SqlWork {
    void run(Connection connection) throws SQLException;
  }

  @FunctionalInterface
  private interface SqlRead<T> {
    T run(Connection connection) throws SQLException;
  }
}
