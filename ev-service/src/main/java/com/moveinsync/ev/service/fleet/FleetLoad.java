package com.moveinsync.ev.service.fleet;

import com.moveinsync.ev.service.plan.TenantSession;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import javax.sql.DataSource;

/**
 * Loads one shard in a repeatable read and releases the connection before mapping.
 *
 * <p>Time O(N) for N catalog rows. Space O(N).
 */
public final class FleetLoad implements FleetSource {

  private final DataSource dataSource;

  public FleetLoad(DataSource dataSource) {
    this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
  }

  @Override
  public LoadedFleet load(String tenant, String shard) {
    FleetSnapshot snapshot = read(tenant, shard);
    return new LoadedFleet(
        new PlannerFleetMapper().map(snapshot),
        new ValidationFleetMapper().map(snapshot),
        snapshot.shard().catalogVersion());
  }

  private FleetSnapshot read(String tenant, String shard) {
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
      connection.setReadOnly(true);
      TenantSession.apply(connection, tenant);
      FleetSnapshot snapshot = new FleetReader().read(connection, tenant, shard);
      connection.rollback();
      return snapshot;
    } catch (FleetRejected rejected) {
      throw rejected;
    } catch (SQLException ex) {
      throw new FleetRejected("STORE_FAILED", shard, ex);
    }
  }
}
