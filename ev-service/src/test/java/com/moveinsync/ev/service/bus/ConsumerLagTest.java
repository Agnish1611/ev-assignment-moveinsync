package com.moveinsync.ev.service.bus;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Only the records-lag series is summed, and a non-positive value is ignored. */
class ConsumerLagTest {

  @Test
  void onlyPositiveRecordsLagIsAdded() {
    long sum = ConsumerLag.add(0L, "records-lag", Integer.valueOf(4));
    sum = ConsumerLag.add(sum, "records-lag-max", Integer.valueOf(9));
    sum = ConsumerLag.add(sum, "records-lag", Integer.valueOf(0));
    sum = ConsumerLag.add(sum, "records-lag", "nope");

    assertThat(sum).isEqualTo(4L);
  }
}
