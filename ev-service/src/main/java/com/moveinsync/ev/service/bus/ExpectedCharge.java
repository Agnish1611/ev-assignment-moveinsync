package com.moveinsync.ev.service.bus;

import java.util.OptionalLong;

/** Plan SoC the drift detector compares with a reading. Empty means the cab is not on the head. */
@FunctionalInterface
public interface ExpectedCharge {

  OptionalLong expectedWh(String cabId);
}
