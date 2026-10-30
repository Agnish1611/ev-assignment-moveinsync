# Latency

Numbers below are from runs on 2026-10-31. They are not the 300-cab solve. That solve stays in `PLANNER_300CAB_REPORT.md` at 24.258 s.

## Hardware

- **OS:** macOS Darwin arm64
- **Processor:** Apple M4 (10 cores)
- **Memory:** 16 GB (17,179,869,184 bytes)
- **JDK:** Temurin 21.0.13+9-LTS

## Construction scale

`ScaleCurveTest` times `GreedyConstructionSolver.solve` only. The solver clock is fixed at 2026-10-30T08:00:00Z with a 30 second deadline, seed 11. Each point passed the validator with every trip assigned.

The base is 10 cabs and five trips each. Chargers follow `cabs * 40 / 300`, at least one.

| Factor | Cabs | Chargers | Trips | Solve |
|---|---:|---:|---:|---:|
| 1x | 10 | 1 | 50 | 18 ms |
| 2x | 20 | 2 | 100 | 27 ms |
| 5x | 50 | 6 | 250 | 164 ms |
| 10x | 100 | 13 | 500 | 1,012 ms |

The case-study fleet is 300 cabs, 40 chargers, and 1,500 trips, and that construction already uses 24.258 s of the 30 second budget. This run does not measure a fleet larger than that. Construction is O(T² · C · H · P), so that 300-cab point is where a single build stops fitting the budget on this machine.

## JMH

`ChargingBenchmark.durationAcrossTheKnee` times one two-segment duration: 10,000 Wh to 50,000 Wh on a 60,000 Wh pack at 50 kW.

- **JMH:** 1.37
- **Mode:** average time, one fork
- **Warmup:** 3 iterations of 1 s
- **Measurement:** 5 iterations of 1 s
- **Result:** 1.908 ± 0.132 ns/op (99.9 percent confidence)
- **VM options:** none. Compiler blackholes were in use.

The annotation processor is not on the verify compile. Generated JMH sources call `Thread.setPriority`, and Error Prone rejects that under `-Werror`. The run compiled the benchmark with `jmh-generator-annprocess` 1.37 into a side directory, then invoked `org.openjdk.jmh.Main`.

## k6

`ev-bench/k6/health.js` against `GET /actuator/health` on a local process. Database health was turned off for this probe. The body was `{"status":"UP"}`. This is the public health scrape, not plan publication.

- **k6 image:** `grafana/k6` digest `sha256:3e4186aea62e11a45560bfc51e19eeafe82fc07ce069c78aff44b7ff893d9a5e`
- **Load:** 10 virtual users for 15 s
- **Requests:** 186,023, all status 200
- **Throughput:** 12,401.5 req/s
- **Duration:** average 755.42 µs, p50 684.12 µs, p99 1.7 ms, max 14.03 ms
