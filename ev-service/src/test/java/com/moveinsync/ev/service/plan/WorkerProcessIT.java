package com.moveinsync.ev.service.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.moveinsync.ev.service.api.CatalogService;
import com.moveinsync.ev.service.bus.OutboxPump;
import com.moveinsync.ev.service.controller.AdminController;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** The worker relays and does not serve catalog writes. */
@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class WorkerProcessIT {

  private static final TokenFixture TOKENS = TokenFixture.start();

  @Autowired private MockMvc mvc;
  @Autowired private ObjectProvider<CatalogService> catalog;
  @Autowired private ObjectProvider<AdminController> admin;
  @Autowired private ObjectProvider<OutboxPump> pump;
  @Autowired private ObjectProvider<JobReaper> reaper;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    PlanDatabase.open();
    registry.add("spring.datasource.url", PlanDatabase::jdbcUrl);
    registry.add("spring.datasource.username", PlanDatabase::databaseUser);
    registry.add("spring.datasource.password", PlanDatabase::databasePassword);
    registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", TOKENS::jwkUri);
    registry.add("spring.flyway.enabled", () -> "false");
    registry.add("ev.schema.migrate", () -> "false");
    registry.add("ev.roles", () -> "worker,detector");
    registry.add("ev.bus.bootstrap", KafkaBrokers::ready);
    registry.add("ev.bus.group", () -> "role-worker");
    registry.add("ev.bus.relay-millis", () -> "3600000");
    registry.add("ev.job.reap-millis", () -> "3600000");
  }

  @AfterAll
  static void stop() {
    TOKENS.stop();
  }

  @Test
  void healthIsUpAndCatalogWritesAreAbsent() throws Exception {
    mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    assertThat(pump.getIfAvailable()).isNotNull();
    assertThat(reaper.getIfAvailable()).isNotNull();
    assertThat(catalog.getIfAvailable()).isNull();
    assertThat(admin.getIfAvailable()).isNull();
  }
}
