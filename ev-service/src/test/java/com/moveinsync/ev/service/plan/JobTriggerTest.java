package com.moveinsync.ev.service.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.moveinsync.ev.service.api.ApiException;
import com.moveinsync.ev.service.api.JobAcceptance;
import com.moveinsync.ev.service.api.JobClaims;
import com.moveinsync.ev.service.api.JobSettings;
import com.moveinsync.ev.service.bus.Envelope;
import com.moveinsync.ev.service.bus.EnvelopeCodec;
import com.moveinsync.ev.service.bus.Topics;
import com.moveinsync.ev.service.repository.JobRepository;
import com.moveinsync.ev.service.repository.PlanHeadRepository;
import com.moveinsync.ev.service.repository.TriggerSequences;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The job and the trigger commit together. An expired lease returns to ACCEPTED. */
class JobTriggerTest {

  private static final String TENANT = "tenant-trigger";
  private static final Instant AT = Instant.parse("2026-10-30T12:00:00Z");

  private final JobAcceptance acceptance =
      new JobAcceptance(new JobRepository(), new PlanHeadRepository(), new TriggerSequences());
  private final JobRepository jobs = new JobRepository();

  @BeforeEach
  void tenant() throws SQLException {
    try (Connection connection = PlanDatabase.open().getConnection()) {
      connection.setAutoCommit(false);
      TenantSession.apply(connection, TENANT);
      new PlanHeadRepository().ensureTenant(connection, TENANT);
      connection.commit();
    }
  }

  @Test
  void aCrashBetweenTheStatementsStoresNeither() throws SQLException {
    try (Connection connection = PlanDatabase.open().getConnection()) {
      connection.setAutoCommit(false);
      TenantSession.apply(connection, TENANT);
      JobAcceptance.Request request = request("shard-crash", null, "job-crash");
      assertThatThrownBy(
              () ->
                  acceptance.write(
                      connection,
                      request,
                      () -> {
                        throw new IllegalStateException("crash");
                      }))
          .isInstanceOf(IllegalStateException.class);
      assertThat(count(connection, "plan_job", "job-crash")).isEqualTo(1L);
      assertThat(count(connection, "outbox", "shard-crash")).isZero();
      connection.rollback();
      assertThat(count(connection, "plan_job", "job-crash")).isZero();
      assertThat(count(connection, "outbox", "shard-crash")).isZero();
    }
  }

  @Test
  void theJobAndTheTriggerShareOneSequence() throws SQLException {
    String tenant = "tenant-trigger-seq";
    try (Connection connection = PlanDatabase.open().getConnection()) {
      connection.setAutoCommit(false);
      TenantSession.apply(connection, tenant);
      new PlanHeadRepository().ensureTenant(connection, tenant);
      JobAcceptance.Result first =
          acceptance.write(connection, request(tenant, "shard-seq", null, "job-seq-1"), () -> {});
      JobAcceptance.Result second =
          acceptance.write(connection, request(tenant, "shard-seq", null, "job-seq-2"), () -> {});
      connection.commit();
      assertThat(first.sequence()).isEqualTo(1L);
      assertThat(second.sequence()).isEqualTo(2L);
      assertThat(first.mode()).isEqualTo(Envelope.PlanRequested.INITIAL);
    }
    assertThat(PlanDatabase.text("SELECT topic FROM outbox WHERE shard = ?", "shard-seq"))
        .isEqualTo(Topics.PLAN_TRIGGERS);
    Envelope envelope =
        EnvelopeCodec.read(
            Base64.getDecoder()
                .decode(
                    PlanDatabase.text(
                        "SELECT payload FROM outbox WHERE shard = ? ORDER BY outbox_id",
                        "shard-seq")));
    assertThat(envelope.sequence()).isEqualTo(1L);
    assertThat(envelope.payload())
        .isEqualTo(new Envelope.PlanRequested("shard-seq", "job-seq-1", "INITIAL"));
    assertThat(PlanDatabase.count("SELECT count(*) FROM plan_job WHERE shard = ?", "shard-seq"))
        .isEqualTo(2L);
  }

  @Test
  void anExistingHeadSelectsAFullReplan() throws SQLException {
    try (Connection connection = PlanDatabase.open().getConnection()) {
      connection.setAutoCommit(false);
      TenantSession.apply(connection, TENANT);
      assertThatThrownBy(
              () ->
                  acceptance.write(
                      connection, request("shard-empty", "FULL_REPLAN", "job-empty"), () -> {}))
          .isInstanceOfSatisfying(
              ApiException.class, failure -> assertThat(failure.code()).isEqualTo("NO_HEAD"));
      assertThatThrownBy(
              () ->
                  acceptance.write(
                      connection, request("shard-empty", "LATER", "job-bad"), () -> {}))
          .isInstanceOfSatisfying(
              ApiException.class, failure -> assertThat(failure.code()).isEqualTo("INVALID_MODE"));
      insertHead(connection, "shard-mode");
      assertThatThrownBy(
              () ->
                  acceptance.write(
                      connection, request("shard-mode", "INITIAL", "job-init"), () -> {}))
          .isInstanceOfSatisfying(
              ApiException.class, failure -> assertThat(failure.code()).isEqualTo("HEAD_EXISTS"));
      JobAcceptance.Result stored =
          acceptance.write(connection, request("shard-mode", null, "job-full"), () -> {});
      connection.commit();
      assertThat(stored.mode()).isEqualTo(Envelope.PlanRequested.FULL_REPLAN);
    }
    assertThat(PlanDatabase.text("SELECT mode FROM plan_job WHERE job_id = ?", "job-init"))
        .isEmpty();
  }

  @Test
  void anExpiredLeaseReturnsToAccepted() throws SQLException {
    try (Connection connection = PlanDatabase.open().getConnection()) {
      connection.setAutoCommit(false);
      TenantSession.apply(connection, TENANT);
      acceptance.write(connection, request("shard-lease", null, "job-old"), () -> {});
      acceptance.write(connection, request("shard-lease", null, "job-live"), () -> {});
      connection.commit();
    }
    try (Connection connection = PlanDatabase.open().getConnection()) {
      connection.setAutoCommit(false);
      TenantSession.apply(connection, TENANT);
      assertThat(jobs.claim(connection, TENANT, "job-old", AT.minusSeconds(1))).isEqualTo(1);
      assertThat(
              new JobClaims(jobs, new JobSettings(120, 5_000))
                  .claim(connection, TENANT, "job-live", AT))
          .isEqualTo(1);
      connection.commit();
    }
    int reaped =
        new JobReaper(
                PlanDatabase.open(),
                jobs,
                Clock.fixed(AT, ZoneOffset.UTC),
                new JobSettings(120, 5_000))
            .reap();
    assertThat(reaped).isEqualTo(1);
    assertThat(PlanDatabase.text("SELECT status FROM plan_job WHERE job_id = ?", "job-old"))
        .isEqualTo("ACCEPTED");
    assertThat(PlanDatabase.text("SELECT status FROM plan_job WHERE job_id = ?", "job-live"))
        .isEqualTo("RUNNING");
    try (Connection connection = PlanDatabase.open().getConnection()) {
      connection.setAutoCommit(false);
      TenantSession.apply(connection, TENANT);
      assertThat(jobs.finish(connection, TENANT, "job-live", "PUBLISHED", "", 4L)).isEqualTo(1);
      connection.commit();
    }
    assertThat(
            PlanDatabase.count(
                "SELECT published_version FROM plan_job WHERE job_id = ?", "job-live"))
        .isEqualTo(4L);
  }

  private static JobAcceptance.Request request(String shard, String mode, String jobId) {
    return request(TENANT, shard, mode, jobId);
  }

  private static JobAcceptance.Request request(
      String tenant, String shard, String mode, String jobId) {
    return new JobAcceptance.Request(tenant, shard, mode, jobId, AT);
  }

  private static void insertHead(Connection connection, String shard) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO plan_head (tenant_id, shard, version, updated_at) VALUES (?, ?, 1, ?)")) {
      statement.setString(1, TENANT);
      statement.setString(2, shard);
      statement.setTimestamp(3, Timestamp.from(AT));
      statement.executeUpdate();
    }
  }

  private static long count(Connection connection, String table, String id) throws SQLException {
    String column = "plan_job".equals(table) ? "job_id" : "shard";
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT count(*) FROM " + table + " WHERE " + column + " = ?")) {
      statement.setString(1, id);
      try (ResultSet rows = statement.executeQuery()) {
        if (!rows.next()) {
          return 0L;
        }
        return rows.getLong(1);
      }
    }
  }
}
