package com.moveinsync.ev.service.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Rejects a missing key, a bad token, a leaked correlation id, and a caller over the rate limit.
 */
@SpringBootTest
@AutoConfigureMockMvc
class HttpGuardIT {

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
    registry.add("ev.api.rate-capacity", () -> "3");
  }

  @AfterAll
  static void stopTokens() {
    TOKENS.stop();
  }

  @Test
  void aMissingKeyAndABadTokenAreProblems() throws Exception {
    String operator = "Bearer " + TOKENS.token("tenant-a", "op-key", "OPERATOR");
    mvc.perform(
            post("/v1/plans")
                .header("Authorization", operator)
                .header("X-Correlation-Id", "corr-http")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"shard\":\"shard-missing\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(
            content()
                .contentTypeCompatibleWith(MediaType.parseMediaType("application/problem+json")))
        .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"))
        .andExpect(jsonPath("$.correlationId").value("corr-http"));
    MvcResult mailed =
        mvc.perform(
                post("/v1/plans")
                    .header("Authorization", operator)
                    .header("X-Correlation-Id", "person@example.com")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"shard\":\"shard-mail\"}"))
            .andExpect(status().isBadRequest())
            .andReturn();
    assertThat(mailed.getResponse().getContentAsString()).doesNotContain("@");
    mvc.perform(
            get("/v1/plans/current")
                .param("shard", "shard-http")
                .header("Authorization", "Bearer not-a-token"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    mvc.perform(
            get("/v1/plans/current")
                .param("shard", "shard-http")
                .header("Authorization", "Bearer " + TOKENS.token(null, "op-none", "OPERATOR")))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void theRateLimitRefusesTheCallerPastCapacity() throws Exception {
    String operator = "Bearer " + TOKENS.token("tenant-b", "rate-user", "OPERATOR");
    for (int attempt = 0; attempt < 3; attempt++) {
      mvc.perform(
              get("/v1/plans/current")
                  .param("shard", "shard-rate")
                  .header("Authorization", operator))
          .andExpect(status().isNotFound());
    }
    mvc.perform(
            get("/v1/plans/current").param("shard", "shard-rate").header("Authorization", operator))
        .andExpect(status().isTooManyRequests())
        .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
  }
}
