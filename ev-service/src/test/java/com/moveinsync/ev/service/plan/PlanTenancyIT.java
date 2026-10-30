package com.moveinsync.ev.service.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Row-level security hides one tenant from another and rejects an unset tenant. */
class PlanTenancyIT {

  @BeforeAll
  static void open() {
    PlanDatabase.open();
  }

  @Test
  void everyApplicationTableForcesRowLevelSecurity() {
    String sql =
        "SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace"
            + " WHERE n.nspname = 'public' AND c.relkind = 'r'"
            + " AND c.relname <> 'flyway_schema_history'"
            + " AND (c.relrowsecurity = false OR c.relforcerowsecurity = false)";

    assertThat(names(sql)).isEmpty();
  }

  @Test
  void anotherTenantCannotSeeOrWriteTheHead() throws SQLException {
    try (Connection connection = PlanDatabase.open().getConnection()) {
      insertHub(connection);
      connection.setAutoCommit(false);
      TenantSession.apply(connection, "tenant-b");
      assertThat(countHubs(connection)).isZero();
      assertThatThrownBy(() -> insertForeignHead(connection)).isInstanceOf(SQLException.class);
      connection.rollback();
    }
    try (Connection connection = PlanDatabase.open().getConnection()) {
      connection.setAutoCommit(false);
      TenantSession.apply(connection, "tenant-a");
      assertThat(countHubs(connection)).isEqualTo(1L);
      connection.rollback();
    }
  }

  @Test
  void aSessionWithoutATenantCannotRead() {
    assertThatThrownBy(
            () -> {
              try (Connection connection = PlanDatabase.open().getConnection();
                  Statement statement = connection.createStatement()) {
                connection.setAutoCommit(false);
                statement.execute("SET LOCAL ROLE ev_app");
                statement.execute("SELECT count(*) FROM plan_head");
              }
            })
        .isInstanceOf(SQLException.class);
  }

  private static void insertHub(Connection connection) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.execute(
          "INSERT INTO tenant (tenant_id) VALUES ('tenant-a') ON CONFLICT DO NOTHING");
      statement.execute(
          "INSERT INTO hub (tenant_id, hub_id, name, location_id, zone_id, charger_count)"
              + " VALUES ('tenant-a', 'HUB-ISO', 'Hub', 'LOC-HUB', 'UTC', 4)"
              + " ON CONFLICT DO NOTHING");
    }
  }

  private static long countHubs(Connection connection) throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet rows = statement.executeQuery("SELECT count(*) FROM hub")) {
      if (!rows.next()) {
        return 0L;
      }
      return rows.getLong(1);
    }
  }

  private static void insertForeignHead(Connection connection) throws SQLException {
    String sql =
        "INSERT INTO plan_head (tenant_id, shard, version, updated_at) VALUES ('tenant-a', 'x', 1, ?)";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setTimestamp(1, Timestamp.from(ValidFleet.START));
      statement.executeUpdate();
    }
  }

  private static List<String> names(String sql) {
    List<String> found = new ArrayList<>();
    try (Connection connection = PlanDatabase.open().getConnection();
        Statement statement = connection.createStatement();
        ResultSet rows = statement.executeQuery(sql)) {
      while (rows.next()) {
        found.add(rows.getString(1));
      }
      return found;
    } catch (SQLException ex) {
      throw new IllegalStateException("query failed", ex);
    }
  }
}
