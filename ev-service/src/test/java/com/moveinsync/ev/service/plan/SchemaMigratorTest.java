package com.moveinsync.ev.service.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.Test;

/** The web login is not a superuser and cannot assume the relay or the worker. */
class SchemaMigratorTest {

  private static final String ROLE =
      "SELECT count(*) FROM pg_roles WHERE rolname = 'ev_api'"
          + " AND rolsuper = false AND rolcanlogin = true AND rolinherit = false";
  private static final String MEMBER_APP =
      "SELECT count(*) FROM pg_auth_members m"
          + " JOIN pg_roles granted ON granted.oid = m.roleid"
          + " JOIN pg_roles member ON member.oid = m.member"
          + " WHERE granted.rolname = 'ev_app' AND member.rolname = 'ev_api'";
  private static final String MEMBER_RELAY =
      "SELECT count(*) FROM pg_auth_members m"
          + " JOIN pg_roles granted ON granted.oid = m.roleid"
          + " JOIN pg_roles member ON member.oid = m.member"
          + " WHERE granted.rolname = 'ev_relay' AND member.rolname = 'ev_api'";

  @Test
  void theApiLoginIsNotASuperuser() {
    SchemaMigrator.migrate(
        PlanDatabase.jdbcUrl(), PlanDatabase.databaseUser(), PlanDatabase.databasePassword());
    assertThat(PlanDatabase.count(ROLE)).isEqualTo(1);
    assertThat(PlanDatabase.count(MEMBER_APP)).isEqualTo(1);
    assertThat(PlanDatabase.count(MEMBER_RELAY)).isZero();
    assertThat(PlanDatabase.count(worker())).isEqualTo(1);
  }

  @Test
  void theApiLoginCannotAssumeTheRelayOrTheWorker() throws SQLException {
    try (Connection connection =
        DriverManager.getConnection(PlanDatabase.jdbcUrl(), "ev_api", "ev_password")) {
      try (Statement statement = connection.createStatement()) {
        assertThatThrownBy(() -> statement.execute("SET ROLE ev_relay"))
            .isInstanceOfSatisfying(
                SQLException.class, ex -> assertThat(ex.getSQLState()).isEqualTo("42501"));
        assertThatThrownBy(() -> statement.execute("SET ROLE ev_worker"))
            .isInstanceOfSatisfying(
                SQLException.class, ex -> assertThat(ex.getSQLState()).isEqualTo("42501"));
      }
    }
  }

  @Test
  void theWorkerUsesTheTriggerTenant() throws SQLException {
    execute("INSERT INTO tenant (tenant_id) VALUES ('tenant-role-a') ON CONFLICT DO NOTHING");
    execute("INSERT INTO tenant (tenant_id) VALUES ('tenant-role-b') ON CONFLICT DO NOTHING");
    execute(
        "INSERT INTO hub (tenant_id, hub_id, name, location_id, zone_id, charger_count)"
            + " VALUES ('tenant-role-a', 'HUB-A', 'A', 'LOC-A', 'UTC', 1) ON CONFLICT DO NOTHING");
    execute(
        "INSERT INTO hub (tenant_id, hub_id, name, location_id, zone_id, charger_count)"
            + " VALUES ('tenant-role-b', 'HUB-B', 'B', 'LOC-B', 'UTC', 1) ON CONFLICT DO NOTHING");
    try (Connection denied =
        DriverManager.getConnection(PlanDatabase.jdbcUrl(), "ev_worker", "ev_password")) {
      try (Statement statement = denied.createStatement()) {
        assertThatThrownBy(() -> statement.executeQuery("SELECT hub_id FROM hub"))
            .isInstanceOf(SQLException.class);
      }
    }
    try (Connection connection =
        DriverManager.getConnection(PlanDatabase.jdbcUrl(), "ev_worker", "ev_password")) {
      connection.setAutoCommit(false);
      WorkerSession.apply(connection, "tenant-role-a");
      try (Statement statement = connection.createStatement();
          ResultSet rows = statement.executeQuery("SELECT hub_id FROM hub")) {
        assertThat(rows.next()).isTrue();
        assertThat(rows.getString(1)).isEqualTo("HUB-A");
        assertThat(rows.next()).isFalse();
      }
      connection.rollback();
    }
  }

  private static void execute(String sql) throws SQLException {
    try (Connection connection = PlanDatabase.open().getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute(sql);
    }
  }

  private static String worker() {
    return "SELECT count(*) FROM pg_roles WHERE rolname = 'ev_worker'"
        + " AND rolsuper = false AND rolbypassrls = false AND rolcanlogin = true"
        + " AND rolinherit = false";
  }

  @Test
  void theApiLoginReadsOnlyAfterAssumingTheAppRole() throws SQLException {
    try (Connection connection =
        DriverManager.getConnection(PlanDatabase.jdbcUrl(), "ev_api", "ev_password")) {
      try (Statement statement = connection.createStatement()) {
        assertThatThrownBy(() -> statement.executeQuery("SELECT count(*) FROM plan_head"))
            .isInstanceOf(SQLException.class);
      }
      connection.setAutoCommit(false);
      TenantSession.apply(connection, "tenant-a");
      try (Statement statement = connection.createStatement();
          ResultSet rows = statement.executeQuery("SELECT count(*) FROM plan_head")) {
        assertThat(rows.next()).isTrue();
      }
      connection.rollback();
    }
  }
}
