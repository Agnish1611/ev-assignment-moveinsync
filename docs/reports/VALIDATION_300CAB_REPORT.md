# 300-cab validator

Numbers below are from the validator benchmark on the 300-cab plan. They are not the planner solve. That solve is `PLANNER_300CAB_REPORT.md`.

## Scale

- **Cabs:** 300 (`CAB-0` to `CAB-299`)
- **Chargers:** 40 (`CHG-00` to `CHG-39`) on one hub
- **Trips:** 1,500 (five per cab)
- **Sessions:** 300, one per cab, staggered
- **Result:** valid. No H1–H6 violations.

## Latency

- **Warmup:** 5 iterations
- **Measured:** 20 iterations
- **p50:** 3.290 ms
- **p99:** 5.113 ms

## Hardware

- **OS:** macOS Darwin arm64
- **Processor:** Apple M4 (10 cores)
- **Memory:** 16 GB
- **JVM:** OpenJDK 21.0.13+9-LTS
