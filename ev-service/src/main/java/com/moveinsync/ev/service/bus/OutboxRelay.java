package com.moveinsync.ev.service.bus;

import com.moveinsync.ev.service.plan.PlanStoreException;
import com.moveinsync.ev.service.plan.RelaySession;
import com.moveinsync.ev.service.repository.OutboxRepository;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import javax.sql.DataSource;

/**
 * Publishes outbox rows and deletes them in the same transaction. A crash after the send and before
 * the delete delivers the version again. Consumers keep the highest version.
 */
public final class OutboxRelay {

  private final MessageBus bus;
  private final OutboxRepository outbox;
  private final BusSettings settings;

  public OutboxRelay(MessageBus bus, OutboxRepository outbox, BusSettings settings) {
    this.bus = Objects.requireNonNull(bus, "bus must not be null");
    this.outbox = Objects.requireNonNull(outbox, "outbox must not be null");
    this.settings = Objects.requireNonNull(settings, "settings must not be null");
  }

  public int publishPending(DataSource dataSource) {
    Objects.requireNonNull(dataSource, "dataSource must not be null");
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      return publish(connection);
    } catch (SQLException ex) {
      throw failed(ex);
    }
  }

  private int publish(Connection connection) throws SQLException {
    try {
      RelaySession.apply(connection);
      List<OutboxRepository.OutboxRow> rows = outbox.claim(connection, settings.outboxBatch());
      for (OutboxRepository.OutboxRow row : rows) {
        bus.send(event(row));
        outbox.delete(connection, row.tenant(), row.id());
      }
      connection.commit();
      return rows.size();
    } catch (RuntimeException | SQLException ex) {
      connection.rollback();
      throw failed(ex);
    }
  }

  private BusRecord event(OutboxRepository.OutboxRow row) {
    Envelope envelope =
        new Envelope(
            row.tenant() + ":" + row.shard() + ":" + row.version(),
            row.tenant(),
            "outbox",
            row.id(),
            row.createdAt(),
            Envelope.SCHEMA_VERSION,
            new Envelope.PlanPublished(row.shard(), row.version()));
    return BusRecord.of(
        Topics.PLAN_EVENTS, row.tenant() + ":" + row.shard(), EnvelopeCodec.write(envelope));
  }

  private static PlanStoreException failed(Exception failure) {
    if (failure instanceof PlanStoreException stored) {
      throw stored;
    }
    return new PlanStoreException("outbox relay failed", failure);
  }
}
