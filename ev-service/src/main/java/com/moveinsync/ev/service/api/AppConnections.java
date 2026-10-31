package com.moveinsync.ev.service.api;

import com.moveinsync.ev.service.bus.BusException;
import com.moveinsync.ev.service.fault.CallBlocked;
import com.moveinsync.ev.service.fault.CallGate;
import com.moveinsync.ev.service.plan.TenantSession;
import com.moveinsync.ev.service.repository.PlanHeadRepository;
import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;

/** One transaction as {@code ev_app} with the token tenant. SQL text stays out of the response. */
final class AppConnections {

  private final ObjectProvider<DataSource> sources;
  private final CallGate gate;
  private final int retryAfterSeconds;

  AppConnections(ObjectProvider<DataSource> sources, CallGate gate, int retryAfterSeconds) {
    this.sources = sources;
    this.gate = gate;
    this.retryAfterSeconds = retryAfterSeconds;
  }

  <T> T read(String tenant, SqlWork<T> work) {
    return run(tenant, work, false);
  }

  <T> T change(String tenant, SqlWork<T> work) {
    return run(tenant, work, true);
  }

  private <T> T run(String tenant, SqlWork<T> work, boolean ensureTenant) {
    try {
      gate.enter();
    } catch (CallBlocked ex) {
      throw unavailable();
    }
    try (Connection connection = sources.getObject().getConnection()) {
      connection.setAutoCommit(false);
      try {
        TenantSession.apply(connection, tenant);
        if (ensureTenant) {
          new PlanHeadRepository().ensureTenant(connection, tenant);
        }
        T value = work.apply(connection);
        connection.commit();
        gate.success();
        return value;
      } catch (ApiException ex) {
        connection.rollback();
        note(ex);
        throw ex;
      } catch (BusException ex) {
        connection.rollback();
        gate.success();
        throw unavailable();
      } catch (SQLException ex) {
        connection.rollback();
        throw stored(ex);
      } catch (RuntimeException ex) {
        connection.rollback();
        gate.success();
        throw abort(ex);
      }
    } catch (SQLException ex) {
      throw stored(ex);
    }
  }

  private void note(ApiException failure) {
    if (failure.status() == 503) {
      gate.failure();
      return;
    }
    gate.success();
  }

  private static ApiException abort(RuntimeException failure) {
    if (failure instanceof ApiException api) {
      throw api;
    }
    if (failure instanceof IllegalArgumentException illegal) {
      throw illegal;
    }
    if (failure instanceof IllegalStateException illegal) {
      throw illegal;
    }
    return new ApiException(500, "STORE_FAILED", "The request could not be stored");
  }

  private ApiException stored(SQLException failure) {
    if ("23505".equals(failure.getSQLState())) {
      gate.success();
      return new ApiException(409, "CONFLICT", "The resource already exists");
    }
    gate.failure();
    return unavailable();
  }

  private ApiException unavailable() {
    return new ApiException(503, "UNAVAILABLE", "The service is not ready", retryAfterSeconds);
  }

  @FunctionalInterface
  interface SqlWork<T> {
    T apply(Connection connection) throws SQLException;
  }
}
