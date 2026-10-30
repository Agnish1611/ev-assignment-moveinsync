package com.moveinsync.ev.service.api;

import com.moveinsync.ev.service.plan.TenantSession;
import com.moveinsync.ev.service.repository.PlanHeadRepository;
import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;

/** One transaction as {@code ev_app} with the token tenant. SQL text stays out of the response. */
final class AppConnections {

  private final ObjectProvider<DataSource> sources;

  AppConnections(ObjectProvider<DataSource> sources) {
    this.sources = sources;
  }

  <T> T read(String tenant, SqlWork<T> work) {
    return run(tenant, work, false);
  }

  <T> T change(String tenant, SqlWork<T> work) {
    return run(tenant, work, true);
  }

  private <T> T run(String tenant, SqlWork<T> work, boolean ensureTenant) {
    try (Connection connection = sources.getObject().getConnection()) {
      connection.setAutoCommit(false);
      try {
        TenantSession.apply(connection, tenant);
        if (ensureTenant) {
          new PlanHeadRepository().ensureTenant(connection, tenant);
        }
        T value = work.apply(connection);
        connection.commit();
        return value;
      } catch (ApiException ex) {
        connection.rollback();
        throw ex;
      } catch (SQLException ex) {
        connection.rollback();
        throw stored(ex);
      } catch (RuntimeException ex) {
        connection.rollback();
        throw abort(ex);
      }
    } catch (SQLException ex) {
      throw stored(ex);
    }
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

  private static ApiException stored(SQLException failure) {
    if ("23505".equals(failure.getSQLState())) {
      return new ApiException(409, "CONFLICT", "The resource already exists");
    }
    return new ApiException(500, "STORE_FAILED", "The request could not be stored");
  }

  @FunctionalInterface
  interface SqlWork<T> {
    T apply(Connection connection) throws SQLException;
  }
}
