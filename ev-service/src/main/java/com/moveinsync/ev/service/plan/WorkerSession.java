package com.moveinsync.ev.service.plan;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Opens a worker transaction. The tenant is the one carried on the trigger envelope. The worker has
 * no token, so it cannot read the tenant from a request. The web process wrote that envelope from
 * the token before the trigger was sent.
 */
public final class WorkerSession {

  private WorkerSession() {
    throw new IllegalStateException("utility");
  }

  public static void apply(Connection connection, String tenantId) throws SQLException {
    TenantSession.apply(connection, tenantId);
  }
}
