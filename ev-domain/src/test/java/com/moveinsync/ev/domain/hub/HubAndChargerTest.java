package com.moveinsync.ev.domain.hub;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.moveinsync.ev.domain.location.Location;
import java.time.ZoneId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class HubAndChargerTest {

  @Test
  @DisplayName("Hub construction and validation")
  void validHubCreation() {
    Location loc = Location.of("LOC-HUB-1", "Electronic City Hub");
    Hub hub = Hub.of("HUB-1", "EC Hub 1", loc, ZoneId.of("Asia/Kolkata"), 10);

    assertThat(hub.id()).isEqualTo(HubId.of("HUB-1"));
    assertThat(hub.name()).isEqualTo("EC Hub 1");
    assertThat(hub.timeZone()).isEqualTo(ZoneId.of("Asia/Kolkata"));
    assertThat(hub.chargerCount()).isEqualTo(10);
  }

  @Test
  @DisplayName("Charger effective power enforces min(charger kW, vehicle max kW)")
  void effectivePowerCalculation() {
    Charger charger50Kw = Charger.of("CHG-1", "HUB-1", 50);

    // Case 1: Charger power < Vehicle max power (50 kW < 80 kW) -> 50 kW
    assertThat(charger50Kw.calculateEffectivePowerKw(80)).isEqualTo(50);

    // Case 2: Vehicle max power < Charger power (30 kW < 50 kW) -> 30 kW
    assertThat(charger50Kw.calculateEffectivePowerKw(30)).isEqualTo(30);

    // Case 3: Charger power == Vehicle max power -> 50 kW
    assertThat(charger50Kw.calculateEffectivePowerKw(50)).isEqualTo(50);
  }

  @Test
  @DisplayName("Charger status transitions and availability")
  void chargerStatusTransitions() {
    Charger charger = Charger.of("CHG-1", "HUB-1", 60);
    assertThat(charger.status()).isEqualTo(ChargerStatus.OPERATIONAL);
    assertThat(charger.status().isAvailable()).isTrue();

    Charger faulty = charger.withStatus(ChargerStatus.FAULTY);
    assertThat(faulty.status()).isEqualTo(ChargerStatus.FAULTY);
    assertThat(faulty.status().isAvailable()).isFalse();

    Charger maint = charger.withStatus(ChargerStatus.MAINTENANCE);
    assertThat(maint.status()).isEqualTo(ChargerStatus.MAINTENANCE);
    assertThat(maint.status().isAvailable()).isFalse();
  }

  @Test
  @DisplayName("Charger rejects negative power and null arguments")
  void chargerValidation() {
    assertThatThrownBy(() -> Charger.of("CHG-1", "HUB-1", 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Charger(null, HubId.of("HUB-1"), 50, ChargerStatus.OPERATIONAL))
        .isInstanceOf(NullPointerException.class);
  }
}
