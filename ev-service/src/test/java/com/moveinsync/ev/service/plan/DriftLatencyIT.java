package com.moveinsync.ev.service.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Arrays;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * One hundred 22 percent readings through HTTP and Kafka. Each repair stays inside two seconds and
 * changes at most three assignments.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DriftLatencyIT {

  private static final Logger log = LoggerFactory.getLogger(DriftLatencyIT.class);
  private static final int SAMPLES = 100;
  private static final long LIMIT_NANOS = 2_000_000_000L;
  private static final TokenFixture TOKENS = TokenFixture.start();

  @Autowired private MockMvc mvc;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    PlanDatabase.open();
    registry.add("spring.datasource.url", PlanDatabase::jdbcUrl);
    registry.add("spring.datasource.username", PlanDatabase::databaseUser);
    registry.add("spring.datasource.password", PlanDatabase::databasePassword);
    registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", TOKENS::jwkUri);
    registry.add("spring.flyway.enabled", () -> "false");
    registry.add("ev.schema.migrate", () -> "false");
    registry.add("ev.roles", () -> "api,worker,detector");
    registry.add("ev.bus.bootstrap", KafkaBrokers::ready);
    registry.add("ev.bus.group", () -> "drift-latency");
    registry.add("ev.bus.relay-millis", () -> "3600000");
    registry.add("ev.job.reap-millis", () -> "3600000");
  }

  @AfterAll
  static void stop() throws SQLException {
    try (Connection connection = PlanDatabase.open().getConnection();
        PreparedStatement statement =
            connection.prepareStatement("DELETE FROM outbox WHERE tenant_id LIKE 'tenant-lat%'")) {
      statement.executeUpdate();
    }
    TOKENS.stop();
  }

  @Test
  void oneHundredReadingsStayInsideTwoSeconds() throws Exception {
    repair("tenant-lat-w", "lat-w", 20_000_000_000L);
    long[] samples = new long[SAMPLES];
    for (int index = 0; index < SAMPLES; index++) {
      String tenant = "tenant-lat-" + index;
      samples[index] = repair(tenant, "lat-" + index, LIMIT_NANOS);
      assertThat(samples[index]).isLessThan(LIMIT_NANOS);
    }
    Arrays.sort(samples);
    long p50 = samples[49] / 1_000_000L;
    long p99 = samples[98] / 1_000_000L;
    long max = samples[SAMPLES - 1] / 1_000_000L;
    log.info("drift latency milliseconds p50 {} p99 {} max {}", p50, p99, max);
    assertThat(max).isLessThan(LIMIT_NANOS / 1_000_000L);
  }

  private long repair(String tenant, String key, long limitNanos) throws Exception {
    DriftFleet.publish(PlanDatabase.open(), DriftFleet.fortyOne(tenant));
    String holder = cab(tenant, 1L);
    DriftFleet.slack(tenant, holder);
    String token = "Bearer " + TOKENS.token(tenant, key, "OPERATOR");
    long started = System.nanoTime();
    mvc.perform(
            post("/v1/events/battery")
                .header("Authorization", token)
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(holder)))
        .andExpect(status().isAccepted());
    while (System.nanoTime() - started < limitNanos && version(tenant) < 2L) {
      Thread.onSpinWait();
    }
    long elapsed = System.nanoTime() - started;
    assertThat(version(tenant)).isEqualTo(2L);
    assertThat(cab(tenant, 2L)).isNotEqualTo(holder);
    assertThat(changes(tenant)).isLessThanOrEqualTo(3L);
    return elapsed;
  }

  private static String body(String cabId) {
    return "{\"cabId\":\""
        + cabId
        + "\",\"socWh\":13200,\"observedAt\":\"2026-10-30T08:00:00Z\",\"sequence\":1}";
  }

  private static String cab(String tenant, long version) {
    return PlanDatabase.text(
        "SELECT vehicle_id FROM route_stop WHERE tenant_id = ? AND shard = ?"
            + " AND version = ? AND trip_id = 'TRIP-NEXT'",
        tenant,
        DriftFleet.SHARD,
        version);
  }

  private static long version(String tenant) {
    return PlanDatabase.count(
        "SELECT version FROM plan_head WHERE tenant_id = ? AND shard = ?",
        tenant,
        DriftFleet.SHARD);
  }

  private static long changes(String tenant) {
    return PlanDatabase.count(
        "SELECT count(*) FROM route_stop a LEFT JOIN route_stop b"
            + " ON b.tenant_id = a.tenant_id AND b.shard = a.shard AND b.trip_id = a.trip_id"
            + " AND b.version = 2 WHERE a.tenant_id = ? AND a.shard = ? AND a.version = 1"
            + " AND (b.vehicle_id IS NULL OR a.vehicle_id <> b.vehicle_id)",
        tenant,
        DriftFleet.SHARD);
  }
}
