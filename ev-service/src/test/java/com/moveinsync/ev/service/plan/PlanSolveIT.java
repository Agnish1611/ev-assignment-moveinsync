package com.moveinsync.ev.service.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.moveinsync.ev.domain.schedule.FleetPlan;
import com.moveinsync.ev.domain.tenant.TenantId;
import com.moveinsync.ev.service.bus.BusRecord;
import com.moveinsync.ev.service.bus.MessageBus;
import com.moveinsync.ev.service.bus.Topics;
import com.moveinsync.ev.service.fleet.FleetLoad;
import com.moveinsync.ev.service.fleet.LoadedFleet;
import com.moveinsync.ev.validator.PlanValidator;
import java.sql.SQLException;
import java.util.Base64;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** A plan post becomes a validator-clean head once the worker reads the trigger. */
@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PlanSolveIT {

  private static final String TENANT = "tenant-solve";
  private static final String SHARD = "shard-http-solve";
  private static final TokenFixture TOKENS = TokenFixture.start();

  @Autowired private MockMvc mvc;
  @Autowired private MessageBus bus;

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
  }

  @BeforeAll
  static void fleet() throws SQLException {
    ScenarioCatalog.store(PlanDatabase.open(), ValidFleet.input(TenantId.of(TENANT)), SHARD);
  }

  @AfterAll
  static void stop() {
    TOKENS.stop();
  }

  @Test
  void aPostPublishesAValidatorCleanHead() throws Exception {
    String operator = "Bearer " + TOKENS.token(TENANT, "solve-op", "OPERATOR");
    MvcResult created =
        mvc.perform(
                post("/v1/plans")
                    .header("Authorization", operator)
                    .header("Idempotency-Key", "solve-http-1")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"shard\":\"" + SHARD + "\"}"))
            .andExpect(status().isAccepted())
            .andReturn();
    String jobId = jobId(created.getResponse().getContentAsString());
    String payload =
        PlanDatabase.text("SELECT payload FROM outbox WHERE shard = ? AND version = 0", SHARD);
    bus.send(
        BusRecord.of(
            Topics.PLAN_TRIGGERS, TENANT + ":" + SHARD, Base64.getDecoder().decode(payload)));
    until(jobId);

    mvc.perform(get("/v1/plans/current").param("shard", SHARD).header("Authorization", operator))
        .andExpect(status().isOk());
    assertThat(PlanDatabase.text("SELECT status FROM plan_job WHERE job_id = ?", jobId))
        .isEqualTo("PUBLISHED");
    FleetPlan plan =
        PlanDocuments.gunzip(
            PlanDatabase.bytes(
                "SELECT compressed_plan FROM plan_snapshot WHERE shard = ? AND version = 1",
                SHARD));
    LoadedFleet fresh = new FleetLoad(PlanDatabase.open()).load(TENANT, SHARD);
    assertThat(new PlanValidator().validate(fresh.validation(), plan).isValid()).isTrue();
  }

  private static String jobId(String body) {
    int marker = body.indexOf("\"jobId\":\"");
    int start = marker + "\"jobId\":\"".length();
    return body.substring(start, body.indexOf('"', start));
  }

  private static void until(String jobId) {
    long deadline = System.nanoTime() + 5_000_000_000L;
    while (System.nanoTime() < deadline) {
      if ("PUBLISHED"
          .equals(PlanDatabase.text("SELECT status FROM plan_job WHERE job_id = ?", jobId))) {
        return;
      }
      Thread.onSpinWait();
    }
    throw new IllegalStateException("job did not publish");
  }
}
