package com.moveinsync.ev.service.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/** A connection that never opens does not put SQL text on the API error. */
class DroppedConnectionTest {

  @Test
  void aDroppedConnectionReturnsStoreFailedWithoutSql() {
    AppConnections connections = new AppConnections(new OneSource(new ClosedDatabase()));

    assertThatThrownBy(() -> connections.read("tenant-a", connection -> "ok"))
        .isInstanceOf(ApiException.class)
        .satisfies(
            thrown -> {
              ApiException error = (ApiException) thrown;
              assertThat(error.status()).isEqualTo(500);
              assertThat(error.code()).isEqualTo("STORE_FAILED");
              assertThat(error.title()).isEqualTo("The request could not be stored");
              assertThat(error.getMessage()).doesNotContain("db.internal");
              assertThat(error.getCause()).isNull();
            });
  }

  private static final class OneSource implements ObjectProvider<DataSource> {
    private final DataSource source;

    private OneSource(DataSource source) {
      this.source = source;
    }

    @Override
    public DataSource getObject() {
      return source;
    }

    @Override
    public DataSource getObject(Object... args) {
      return source;
    }

    @Override
    public DataSource getIfAvailable() {
      return source;
    }

    @Override
    public DataSource getIfUnique() {
      return source;
    }
  }

  private static final class ClosedDatabase implements DataSource {
    @Override
    public Connection getConnection() throws SQLException {
      throw new SQLException("connection reset at db.internal");
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
      throw new SQLException("connection reset at db.internal");
    }

    @Override
    public java.io.PrintWriter getLogWriter() {
      return null;
    }

    @Override
    public void setLogWriter(java.io.PrintWriter out) {
      // The probe never logs through the driver.
    }

    @Override
    public void setLoginTimeout(int seconds) {
      // The probe does not wait on the driver.
    }

    @Override
    public int getLoginTimeout() {
      return 0;
    }

    @Override
    public java.util.logging.Logger getParentLogger()
        throws java.sql.SQLFeatureNotSupportedException {
      throw new java.sql.SQLFeatureNotSupportedException("no logger");
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
      throw new SQLException("not a wrapper");
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) {
      return false;
    }
  }
}
