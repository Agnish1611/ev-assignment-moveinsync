package com.moveinsync.ev.sim;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class FleetSeederTest {

  @Test
  void eveningBodyKeepsOneTripFinishingEarly() {
    String body = FleetSeeder.eveningTrips();
    assertThat(count(body, "\"tripId\"")).isEqualTo(ScaleScenario.TRIP_COUNT + 1);
    assertThat(body).contains("\"tripId\":\"" + FleetSeeder.QUARANTINE_ID + "\"");
    assertThat(body).contains("\"latestFinish\":\"2026-10-30T08:09:59Z\"");
    assertThat(body).contains("\"start\":\"2026-10-30T08:10:00Z\"");
    assertThat(body.length()).isLessThan(2_097_152);
  }

  private static int count(String body, String token) {
    int found = 0;
    int from = 0;
    while (from >= 0) {
      from = body.indexOf(token, from);
      if (from < 0) {
        return found;
      }
      found++;
      from += token.length();
    }
    return found;
  }
}
