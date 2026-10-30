package com.moveinsync.ev.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class EvServiceApplicationTest {

  @Test
  @DisplayName("EvServiceApplication instantiation and main entry point")
  void applicationCoverage() {
    EvServiceApplication app = new EvServiceApplication();
    assertThat(app).isNotNull();
    EvServiceApplication.main(new String[] {"--spring.main.web-application-type=none"});
  }
}
