package com.moveinsync.ev.service.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.moveinsync.ev.service.bus.Topics;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** A caller reads its own job. Another tenant receives 404. */
@SpringBootTest
@AutoConfigureMockMvc
class JobStatusIT {

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
  }

  @BeforeAll
  static void head() throws SQLException {
    try (Connection connection = PlanDatabase.open().getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute(
          "INSERT INTO tenant (tenant_id) VALUES ('tenant-a') ON CONFLICT DO NOTHING");
      statement.execute(
          "INSERT INTO plan_head (tenant_id, shard, version, updated_at)"
              + " VALUES ('tenant-a', 'shard-status-head', 1, '2026-10-30T12:00:00Z')"
              + " ON CONFLICT DO NOTHING");
    }
  }

  @AfterAll
  static void stop() {
    TOKENS.stop();
  }

  @Test
  void theCallerReadsTheJobAndAnotherTenantDoesNot() throws Exception {
    String operator = "Bearer " + TOKENS.token("tenant-a", "job-status-op", "OPERATOR");
    MvcResult created =
        mvc.perform(
                post("/v1/plans")
                    .header("Authorization", operator)
                    .header("Idempotency-Key", "job-status-1")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"shard\":\"shard-status\"}"))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.status").value("ACCEPTED"))
            .andReturn();
    String jobId = jobId(created);
    mvc.perform(get("/v1/plans/jobs/" + jobId).header("Authorization", operator))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("ACCEPTED"))
        .andExpect(jsonPath("$.code").value(""))
        .andExpect(jsonPath("$.publishedVersion").value(0))
        .andExpect(jsonPath("$.mode").value("INITIAL"));
    mvc.perform(
            get("/v1/plans/jobs/" + jobId)
                .header(
                    "Authorization", "Bearer " + TOKENS.token("tenant-b", "job-other", "OPERATOR")))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("JOB_NOT_FOUND"));
    mvc.perform(
            get("/v1/plans/jobs/" + jobId)
                .header(
                    "Authorization", "Bearer " + TOKENS.token("tenant-a", "job-emp", "EMPLOYEE")))
        .andExpect(status().isForbidden());
    assertThat(PlanDatabase.text("SELECT topic FROM outbox WHERE shard = ?", "shard-status"))
        .isEqualTo(Topics.PLAN_TRIGGERS);
    assertThat(PlanDatabase.count("SELECT count(*) FROM plan_job WHERE shard = ?", "shard-status"))
        .isEqualTo(1L);
  }

  @Test
  void initialIsRejectedWhenAHeadExists() throws Exception {
    String operator = "Bearer " + TOKENS.token("tenant-a", "job-status-op", "OPERATOR");
    mvc.perform(
            post("/v1/plans")
                .header("Authorization", operator)
                .header("Idempotency-Key", "job-status-head")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"shard\":\"shard-status-head\",\"mode\":\"INITIAL\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("HEAD_EXISTS"));
    mvc.perform(
            post("/v1/plans")
                .header("Authorization", operator)
                .header("Idempotency-Key", "job-status-full")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"shard\":\"shard-status-head\",\"mode\":\"FULL_REPLAN\"}"))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.status").value("ACCEPTED"));
    assertThat(PlanDatabase.text("SELECT mode FROM plan_job WHERE shard = ?", "shard-status-head"))
        .isEqualTo("FULL_REPLAN");
  }

  private static String jobId(MvcResult result) throws Exception {
    String body = result.getResponse().getContentAsString();
    int start = body.indexOf("\"jobId\":\"") + "\"jobId\":\"".length();
    return body.substring(start, body.indexOf('"', start));
  }
}
