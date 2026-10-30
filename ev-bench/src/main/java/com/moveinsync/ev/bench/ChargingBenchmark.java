package com.moveinsync.ev.bench;

import com.moveinsync.ev.domain.charging.ChargingCurve;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;

/** Times one two-segment charge duration. This is not the fleet solve. Time O(1), space O(1). */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class ChargingBenchmark {

  private static final long START_WH = 10_000L;
  private static final long TARGET_WH = 50_000L;
  private static final long CAPACITY_WH = 60_000L;
  private static final int PLUG_KW = 50;

  private final ChargingCurve curve = new ChargingCurve(80, 50);

  /**
   * Duration from 10 kWh to 50 kWh on a 60 kWh pack at 50 kW, crossing the 80 percent knee.
   *
   * @return seconds, rounded up
   */
  @Benchmark
  public long durationAcrossTheKnee() {
    return curve.calculateDurationSeconds(START_WH, TARGET_WH, CAPACITY_WH, PLUG_KW);
  }
}
