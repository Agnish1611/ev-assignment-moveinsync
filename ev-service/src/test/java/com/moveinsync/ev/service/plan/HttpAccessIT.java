package com.moveinsync.ev.service.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.moveinsync.ev.planner.input.PlannerInput;
import com.moveinsync.ev.service.bus.Envelope;
import com.moveinsync.ev.service.bus.EnvelopeCodec;
import com.moveinsync.ev.service.bus.MessageBus;
import com.moveinsync.ev.service.bus.Topics;
import com.moveinsync.ev.service.repository.PlanHeadRepository;
import com.moveinsync.ev.service.repository.PlanReadRepository;
import com.moveinsync.ev.validator.PlanValidator;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
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

/** The token tenant is the only tenant a request can see. A job is not a published head. */
@SpringBootTest
@AutoConfigureMockMvc
class HttpAccessIT {

  private static final String SHARD = "shard-http";
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
    registry.add("ev.api.rate-capacity", () -> "80");
    registry.add("ev.api.max-body-bytes", () -> "2048");
  }

  @BeforeAll
  static void publish() {
    PlannerInput fleet = ValidFleet.input();
    new PlanPublication(
            new PlanValidator(),
            new PlanHeadRepository(),
            new PlanReadRepository(),
            Clock.fixed(ValidFleet.START, ZoneOffset.UTC))
        .publish(
            PlanDatabase.open(),
            new PublishCommand(
                ValidFleet.TENANT,
                SHARD,
                0L,
                ValidFleet.plan(fleet),
                ValidFleet.groundTruth(fleet),
                "http-reader",
                1L,
                0,
                0,
                1L,
                0L,
                "PUBLISHED",
                0L,
                0L));
  }

  @AfterAll
  static void stopTokens() throws SQLException {
    try (Connection connection = PlanDatabase.open().getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute("DELETE FROM trip WHERE tenant_id = 'tenant-a' AND trip_id = 'TRIP-HTTP'");
      statement.execute("DELETE FROM hub WHERE tenant_id = 'tenant-a' AND hub_id = 'HUB-HTTP'");
    }
    TOKENS.stop();
  }

  @Test
  void anOperatorReadsOnlyItsOwnTenant() throws Exception {
    String operator = bearer("tenant-a", "op-http", "OPERATOR");

    mvc.perform(get("/v1/plans/current").param("shard", SHARD).header("Authorization", operator))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version").value(1));
    mvc.perform(get("/v1/plans/1").param("shard", SHARD).header("Authorization", operator))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.planId").isNotEmpty());
    mvc.perform(
            get("/v1/plans/1/diff")
                .param("shard", SHARD)
                .param("from", "0")
                .header("Authorization", operator))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.changes").value(org.hamcrest.Matchers.containsString("TRIP-1")));
    mvc.perform(
            get("/v1/plans/1/validation-report")
                .param("shard", SHARD)
                .header("Authorization", operator))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_PLAIN))
        .andExpect(
            content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.emptyString())));
    mvc.perform(
            get("/v1/plans/1/unassigned").param("shard", SHARD).header("Authorization", operator))
        .andExpect(status().isOk());
    mvc.perform(
            get("/v1/cabs/CAB-1/timeline").param("shard", SHARD).header("Authorization", operator))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.stops[0].tripId").value("TRIP-1"));

    mvc.perform(
            get("/v1/plans/current")
                .param("shard", SHARD)
                .param("tenant", "tenant-a")
                .header("Authorization", bearer("tenant-b", "op-other", "OPERATOR")))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("PLAN_NOT_FOUND"));
  }

  @Test
  void anEmployeeCannotReadPlansAndSeesOnlyItsTrips() throws Exception {
    mvc.perform(
            get("/v1/plans/current")
                .param("shard", SHARD)
                .header("Authorization", bearer("tenant-a", "emp-1", "EMPLOYEE")))
        .andExpect(status().isForbidden());
    String admin = bearer("tenant-a", "admin-http", "ADMIN");
    mvc.perform(
            post("/v1/admin/hubs")
                .header("Authorization", admin)
                .header("Idempotency-Key", "hub-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"hubId\":\"HUB-HTTP\",\"name\":\"North\",\"locationId\":\"LOC-HTTP\",\"zone\":\"UTC\",\"chargerCount\":2}"))
        .andExpect(status().isCreated());
    mvc.perform(
            post("/v1/admin/trips")
                .header("Authorization", admin)
                .header("Idempotency-Key", "trip-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"tripId\":\"TRIP-HTTP\",\"start\":\"2026-10-30T09:00:00Z\",\"fromLocation\":\"LOC-P\","
                        + "\"toLocation\":\"LOC-D\",\"distanceMetres\":1000,\"latestFinish\":\"2026-10-30T10:00:00Z\","
                        + "\"employeeRef\":\"emp-1\"}"))
        .andExpect(status().isCreated());

    mvc.perform(
            get("/v1/me/trips").header("Authorization", bearer("tenant-a", "emp-1", "EMPLOYEE")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.trips[0].tripId").value("TRIP-HTTP"))
        .andExpect(jsonPath("$.trips[0].status").value("UNASSIGNED"))
        .andExpect(jsonPath("$.trips[0].cabId").value(""));
    mvc.perform(
            get("/v1/me/trips").header("Authorization", bearer("tenant-b", "emp-1", "EMPLOYEE")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.trips").isEmpty());
  }

  @Test
  void aDeviceCanSendBatteryButCannotReadPlans() throws Exception {
    List<Envelope> seen = new ArrayList<>();
    bus.subscribe(
        Topics.TELEMETRY,
        (record, ack) -> {
          seen.add(EnvelopeCodec.read(record.value()));
          ack.run();
        });

    mvc.perform(
            post("/v1/events/battery")
                .header("Authorization", bearer("tenant-a", "device-1", "DEVICE"))
                .header("Idempotency-Key", "bat-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"cabId\":\"CAB-1\",\"socWh\":24600,\"observedAt\":\"2026-10-30T09:00:00Z\",\"sequence\":1}"))
        .andExpect(status().isAccepted());
    mvc.perform(
            get("/v1/plans/current")
                .param("shard", SHARD)
                .header("Authorization", bearer("tenant-a", "device-1", "DEVICE")))
        .andExpect(status().isForbidden());

    assertThat(seen).hasSize(1);
    assertThat(seen.get(0).tenant()).isEqualTo("tenant-a");
    assertThat(seen.get(0).payload()).isInstanceOf(Envelope.Telemetry.class);
  }

  @Test
  void anOperatorCannotAdministerTheFleet() throws Exception {
    mvc.perform(
            post("/v1/admin/users")
                .header("Authorization", bearer("tenant-a", "op-http", "OPERATOR"))
                .header("Idempotency-Key", "user-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":\"emp-9\",\"role\":\"ADMIN\"}"))
        .andExpect(status().isForbidden());
  }

  @Test
  void aRepeatedPlanRequestReplaysTheJobAndDoesNotPublish() throws Exception {
    String body = "{\"shard\":\"shard-job\",\"tenant\":\"tenant-b\"}";
    String operator = bearer("tenant-a", "op-job", "OPERATOR");
    MvcResult first =
        mvc.perform(
                post("/v1/plans")
                    .header("Authorization", operator)
                    .header("Idempotency-Key", "job-1")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.status").value("ACCEPTED"))
            .andReturn();
    mvc.perform(
            post("/v1/plans")
                .header("Authorization", operator)
                .header("Idempotency-Key", "job-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.jobId").value(jobId(first)));

    assertThat(head("tenant-a", "shard-job")).isZero();
    assertThat(jobs("tenant-a", "shard-job")).isEqualTo(1L);
    assertThat(jobs("tenant-b", "shard-job")).isZero();
  }

  private static String bearer(String tenant, String subject, String role) {
    return "Bearer " + TOKENS.token(tenant, subject, role);
  }

  private static String jobId(MvcResult result) throws Exception {
    String body = result.getResponse().getContentAsString();
    int from = body.indexOf("\"jobId\":\"");
    int start = from + "\"jobId\":\"".length();
    return body.substring(start, body.indexOf('"', start));
  }

  private static long head(String tenant, String shard) {
    return PlanDatabase.count(
        "SELECT version FROM plan_head WHERE tenant_id = ? AND shard = ?", tenant, shard);
  }

  private static long jobs(String tenant, String shard) {
    return PlanDatabase.count(
        "SELECT count(*) FROM plan_job WHERE tenant_id = ? AND shard = ?", tenant, shard);
  }
}
