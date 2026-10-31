package com.moveinsync.ev.service.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.moveinsync.ev.domain.schedule.FleetPlan;
import com.moveinsync.ev.domain.tenant.TenantId;
import com.moveinsync.ev.planner.input.PlannerInput;
import com.moveinsync.ev.service.repository.PlanHeadRepository;
import com.moveinsync.ev.service.repository.PlanReadRepository;
import com.moveinsync.ev.validator.PlanValidator;
import com.moveinsync.ev.validator.ValidationInput;
import java.time.Clock;
import java.time.ZoneOffset;
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

/** A paused database fails readiness. A cached head is still served. Writes return 503. */
@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class StoreOutageIT {

  private static final String TENANT = "tenant-outage";
  private static final String SHARD = "shard-outage";
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
    registry.add("ev.roles", () -> "api");
    registry.add("ev.cache.current-ttl-seconds", () -> "60");
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
  void aPausedDatabaseFailsReadinessAndKeepsTheCachedHead() throws Exception {
    publish();
    String token = "Bearer " + TOKENS.token(TENANT, "outage-op", "OPERATOR");
    mvc.perform(get("/v1/plans/current").header("Authorization", token).param("shard", SHARD))
        .andExpect(status().isOk());
    boolean paused = false;
    try {
      PlanDatabase.pause();
      paused = true;
      long cached = System.nanoTime();
      mvc.perform(get("/v1/plans/current").header("Authorization", token).param("shard", SHARD))
          .andExpect(status().isOk());
      assertThat(System.nanoTime() - cached).isLessThan(1_000_000_000L);
      long probe = System.nanoTime();
      mvc.perform(get("/actuator/health/readiness"))
          .andExpect(status().isServiceUnavailable())
          .andExpect(jsonPath("$.status").value("DOWN"));
      long waited = System.nanoTime() - probe;
      assertThat(waited).isGreaterThan(1_000_000_000L);
      assertThat(waited).isLessThan(8_000_000_000L);
      long second = System.nanoTime();
      mvc.perform(
              post("/v1/plans")
                  .header("Authorization", token)
                  .header("Idempotency-Key", "outage-write")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"shard\":\"shard-outage-next\"}"))
          .andExpect(status().isServiceUnavailable())
          .andExpect(header().string(HttpHeaders.RETRY_AFTER, "2"))
          .andExpect(jsonPath("$.code").value("UNAVAILABLE"))
          .andExpect(jsonPath("$.title").value("The service is not ready"));
      assertThat(System.nanoTime() - second).isLessThan(1_000_000_000L);
      mvc.perform(
              get("/v1/plans/current")
                  .header("Authorization", token)
                  .param("shard", "shard-outage-miss"))
          .andExpect(status().isServiceUnavailable())
          .andExpect(header().string(HttpHeaders.RETRY_AFTER, "2"))
          .andExpect(jsonPath("$.code").value("UNAVAILABLE"));
    } finally {
      if (paused) {
        PlanDatabase.resume();
      }
    }
    long deadline = System.nanoTime() + 15_000_000_000L;
    while (System.nanoTime() < deadline) {
      if (mvc.perform(get("/actuator/health/readiness")).andReturn().getResponse().getStatus()
          == 200) {
        mvc.perform(
                post("/v1/plans")
                    .header("Authorization", token)
                    .header("Idempotency-Key", "outage-recovered")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"shard\":\"shard-outage-recovered\"}"))
            .andExpect(status().isAccepted());
        return;
      }
      Thread.onSpinWait();
    }
    throw new IllegalStateException("readiness did not recover");
  }

  private static void publish() {
    PlannerInput fleet = ValidFleet.input(TenantId.of(TENANT));
    FleetPlan plan = ValidFleet.plan(fleet);
    ValidationInput ground = ValidFleet.groundTruth(fleet);
    PublishResult result =
        new PlanPublication(
                new PlanValidator(),
                new PlanHeadRepository(),
                new PlanReadRepository(),
                Clock.fixed(ValidFleet.START, ZoneOffset.UTC))
            .publish(
                PlanDatabase.open(),
                new PublishCommand(
                    TenantId.of(TENANT),
                    SHARD,
                    0L,
                    plan,
                    ground,
                    "outage-seed",
                    1L,
                    0,
                    0,
                    0L,
                    0L,
                    "INITIAL",
                    0L,
                    0L));
    assertThat(result.outcome()).isEqualTo(PublishOutcome.PUBLISHED);
  }
}
