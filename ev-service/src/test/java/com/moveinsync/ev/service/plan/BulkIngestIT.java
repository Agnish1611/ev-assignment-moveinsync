package com.moveinsync.ev.service.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moveinsync.ev.service.bus.Envelope;
import com.moveinsync.ev.service.bus.EnvelopeCodec;
import com.moveinsync.ev.service.bus.MessageBus;
import com.moveinsync.ev.service.bus.Topics;
import com.moveinsync.ev.sim.FleetSeeder;
import com.moveinsync.ev.sim.ScaleScenario;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
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

/** One catalog post stores the evening trips and quarantines the single bad row. */
@SpringBootTest
@AutoConfigureMockMvc
class BulkIngestIT {

  private static final String TENANT = "tenant-bulk";
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
    registry.add("ev.api.rate-capacity", () -> "2");
    registry.add("ev.api.max-body-bytes", () -> "4194304");
    registry.add("ev.api.max-bulk-items", () -> "2000");
  }

  @AfterAll
  static void stopTokens() {
    TOKENS.stop();
  }

  @Test
  void eveningTripsLoadInOneCallAndOneRowIsQuarantined() throws Exception {
    List<byte[]> config = new ArrayList<>();
    bus.subscribe(
        Topics.CONFIG,
        (record, ack) -> {
          config.add(record.value());
          ack.run();
        });
    String body = FleetSeeder.eveningTrips();
    String admin = "Bearer " + TOKENS.token(TENANT, "bulk-admin", "ADMIN");

    MvcResult posted = postTrips(admin, "evening-trips", body);
    assertBatch(posted);
    assertStored();
    assertConfig(config);

    MvcResult replayed = postTrips(admin, "evening-trips", body);
    assertThat(replayed.getResponse().getContentAsString())
        .isEqualTo(posted.getResponse().getContentAsString());
    assertThat(tripCount()).isEqualTo(ScaleScenario.TRIP_COUNT);
    assertThat(config).hasSize(1);

    mvc.perform(
            post("/v1/admin/hubs")
                .header("Authorization", admin)
                .header("Idempotency-Key", "hub-after-bulk")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"hubId\":\"HUB-1\",\"name\":\"Hub\",\"locationId\":\"LOC-HUB\",\"zone\":\"UTC\",\"chargerCount\":1}"))
        .andExpect(status().isTooManyRequests())
        .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
  }

  private MvcResult postTrips(String admin, String key, String body) throws Exception {
    return mvc.perform(
            post("/v1/admin/trips")
                .header("Authorization", admin)
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isOk())
        .andReturn();
  }

  private static void assertBatch(MvcResult posted) throws Exception {
    String response = posted.getResponse().getContentAsString();
    assertThat(response).doesNotContain("EMP-").doesNotContain("latitude").doesNotContain("@");
    JsonNode results = new ObjectMapper().readTree(response).get("results");
    assertThat(results).hasSize(ScaleScenario.TRIP_COUNT + 1);
    int stored = 0;
    int quarantined = 0;
    for (JsonNode result : results) {
      if ("STORED".equals(result.get("status").asText())) {
        stored++;
        assertThat(result.get("code").asText()).isEmpty();
      } else {
        quarantined++;
        assertThat(result.get("id").asText()).isEqualTo(FleetSeeder.QUARANTINE_ID);
        assertThat(result.get("code").asText()).isEqualTo("INVALID_TIME");
      }
    }
    assertThat(stored).isEqualTo(ScaleScenario.TRIP_COUNT);
    assertThat(quarantined).isEqualTo(1);
  }

  private static void assertStored() {
    assertThat(tripCount()).isEqualTo(ScaleScenario.TRIP_COUNT);
    assertThat(
            PlanDatabase.count(
                "SELECT count(*) FROM trip WHERE tenant_id = ? AND trip_id = ?",
                TENANT,
                FleetSeeder.QUARANTINE_ID))
        .isZero();
    assertThat(
            PlanDatabase.count(
                "SELECT duration_seconds FROM trip WHERE tenant_id = ? AND trip_id = 'TRIP-0000'",
                TENANT))
        .isEqualTo(600L);
    assertThat(
            PlanDatabase.text(
                "SELECT shard_id FROM trip WHERE tenant_id = ? AND trip_id = 'TRIP-0000'", TENANT))
        .isEqualTo(FleetSeeder.SHARD);
    assertThat(
            PlanDatabase.count("SELECT version FROM catalog_version WHERE tenant_id = ?", TENANT))
        .isEqualTo(1L);
    assertThat(
            PlanDatabase.count("SELECT count(*) FROM fleet_location WHERE tenant_id = ?", TENANT))
        .isEqualTo(2L);
  }

  private static void assertConfig(List<byte[]> config) {
    assertThat(config).hasSize(1);
    String raw = new String(config.get(0), StandardCharsets.UTF_8);
    assertThat(raw).doesNotContain("EMP-").doesNotContain("latitude");
    Envelope envelope = EnvelopeCodec.read(config.get(0));
    assertThat(envelope.payload()).isInstanceOf(Envelope.ConfigChanged.class);
    Envelope.ConfigChanged changed = (Envelope.ConfigChanged) envelope.payload();
    assertThat(changed.kind()).isEqualTo("TRIP");
    assertThat(changed.subjectId()).isEqualTo("bulk");
  }

  private static long tripCount() {
    return PlanDatabase.count("SELECT count(*) FROM trip WHERE tenant_id = ?", TENANT);
  }
}
