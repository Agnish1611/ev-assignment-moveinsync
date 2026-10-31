package com.moveinsync.ev.service.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.moveinsync.ev.service.bus.OutboxPump;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** A paused broker delays ingest. The plan trigger stays in the outbox until the broker returns. */
@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class KafkaOutageIT {

  private static final String TENANT = "tenant-kafka-outage";
  private static final TokenFixture TOKENS = TokenFixture.start();

  @Autowired private MockMvc mvc;
  @Autowired private OutboxPump pump;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    PlanDatabase.open();
    registry.add("spring.datasource.url", PlanDatabase::jdbcUrl);
    registry.add("spring.datasource.username", PlanDatabase::databaseUser);
    registry.add("spring.datasource.password", PlanDatabase::databasePassword);
    registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", TOKENS::jwkUri);
    registry.add("spring.flyway.enabled", () -> "false");
    registry.add("ev.schema.migrate", () -> "false");
    registry.add("ev.roles", () -> "api,worker");
    registry.add("ev.bus.bootstrap", KafkaBrokers::ready);
    registry.add("ev.bus.group", () -> "kafka-outage");
    registry.add("ev.bus.relay-millis", () -> "3600000");
    registry.add("ev.job.reap-millis", () -> "3600000");
    registry.add("ev.resilience.connect-timeout-seconds", () -> "2");
    registry.add("ev.resilience.socket-timeout-seconds", () -> "2");
    registry.add("ev.resilience.failure-limit", () -> "1");
    registry.add("ev.resilience.open-millis", () -> "1000");
    registry.add("ev.resilience.retry-after-seconds", () -> "2");
  }

  @AfterAll
  static void stop() {
    TOKENS.stop();
  }

  @Test
  void aPausedBrokerDelaysIngestAndKeepsTheOutbox() throws Exception {
    String token = "Bearer " + TOKENS.token(TENANT, "kafka-op", "OPERATOR");
    boolean paused = false;
    try {
      KafkaBrokers.pause();
      paused = true;
      long started = System.nanoTime();
      mvc.perform(
              post("/v1/events/battery")
                  .header("Authorization", token)
                  .header("Idempotency-Key", "kafka-battery")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(
                      "{\"cabId\":\"CAB-1\",\"socWh\":13200,"
                          + "\"observedAt\":\"2026-10-30T08:00:00Z\",\"sequence\":1}"))
          .andExpect(status().isServiceUnavailable())
          .andExpect(header().string(HttpHeaders.RETRY_AFTER, "2"))
          .andExpect(jsonPath("$.code").value("UNAVAILABLE"));
      long waited = System.nanoTime() - started;
      assertThat(waited).isGreaterThan(1_000_000_000L);
      assertThat(waited).isLessThan(8_000_000_000L);
      mvc.perform(
              post("/v1/plans")
                  .header("Authorization", token)
                  .header("Idempotency-Key", "kafka-plan")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"shard\":\"shard-kafka-outage\"}"))
          .andExpect(status().isAccepted());
      assertThat(outbox()).isPositive();
    } finally {
      if (paused) {
        KafkaBrokers.resume();
      }
    }
    long deadline = System.nanoTime() + 20_000_000_000L;
    while (System.nanoTime() < deadline) {
      pump.publish();
      if (outbox() == 0L) {
        return;
      }
      LockSupport.parkNanos(50_000_000L);
    }
    throw new IllegalStateException("outbox did not drain");
  }

  private static long outbox() {
    return PlanDatabase.count("SELECT count(*) FROM outbox WHERE tenant_id = ?", TENANT);
  }
}
