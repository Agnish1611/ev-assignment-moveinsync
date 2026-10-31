package com.moveinsync.ev.service.bus;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SeenSequencesTest {

  @Test
  void theOldestProducerIsForgottenAtTheBound() {
    SeenSequences seen = new SeenSequences(2);
    seen.commit("tenant-a", "cab-a", 5L);
    seen.commit("tenant-a", "cab-b", 5L);
    seen.commit("tenant-a", "cab-c", 5L);

    assertThat(seen.preview("tenant-a", "cab-a", 5L)).isEqualTo(SeenSequences.Disposition.ACCEPT);
    assertThat(seen.preview("tenant-a", "cab-b", 4L)).isEqualTo(SeenSequences.Disposition.LATE);
    assertThat(seen.preview("tenant-a", "cab-c", 5L))
        .isEqualTo(SeenSequences.Disposition.DUPLICATE);
  }
}
