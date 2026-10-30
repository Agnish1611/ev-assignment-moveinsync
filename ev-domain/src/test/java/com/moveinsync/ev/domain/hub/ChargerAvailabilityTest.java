package com.moveinsync.ev.domain.hub;

import static org.assertj.core.api.Assertions.assertThat;

import com.moveinsync.ev.domain.tenant.TenantId;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ChargerAvailabilityTest {

  @Test
  @DisplayName("Charger availability accounts for static status and dynamic outage windows")
  void chargerAvailabilityRules() {
    TenantId tenantId = TenantId.of("tenant-1");
    HubId hubId = HubId.of("HUB-1");
    ChargerId chargerId = ChargerId.of("CHG-1");

    Instant t10 = Instant.parse("2026-10-30T10:00:00Z");
    Instant t11 = Instant.parse("2026-10-30T11:00:00Z");
    OutageWindow outage = new OutageWindow(t10, t11, "Grid curtailment");

    Charger charger =
        new Charger(tenantId, chargerId, hubId, 50, ChargerStatus.AVAILABLE, List.of(outage));

    // Available before outage: [09:00, 10:00)
    Instant t09 = Instant.parse("2026-10-30T09:00:00Z");
    assertThat(charger.isAvailableDuring(t09, t10)).isTrue();

    // Available after outage: [11:00, 12:00)
    Instant t12 = Instant.parse("2026-10-30T12:00:00Z");
    assertThat(charger.isAvailableDuring(t11, t12)).isTrue();

    // Unavailable during outage: [10:30, 11:30)
    Instant t1030 = Instant.parse("2026-10-30T10:30:00Z");
    Instant t1130 = Instant.parse("2026-10-30T11:30:00Z");
    assertThat(charger.isAvailableDuring(t1030, t1130)).isFalse();

    // Faulty charger is unavailable regardless of outage windows
    Charger faulty = new Charger(tenantId, chargerId, hubId, 50, ChargerStatus.FAULTY, List.of());
    assertThat(faulty.isAvailableDuring(t09, t10)).isFalse();
  }
}
