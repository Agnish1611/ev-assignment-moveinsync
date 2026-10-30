package com.moveinsync.ev.service.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.Test;

/** The packaged login can assume the application roles and is not a superuser. */
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
    assertThat(PlanDatabase.count(MEMBER_RELAY)).isEqualTo(1);
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
