package com.moveinsync.ev.planner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.moveinsync.ev.domain.DomainMarker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PlannerMarkerTest {

  @Test
  @DisplayName("PlannerMarker constructor and null guard")
  void plannerMarkerCoverage() {
    DomainMarker dm = new DomainMarker(1);
    PlannerMarker pm = new PlannerMarker(dm);
    assertThat(pm.domainMarker()).isSameAs(dm);

    assertThatThrownBy(() -> new PlannerMarker(null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("domainMarker must not be null");
  }
}
