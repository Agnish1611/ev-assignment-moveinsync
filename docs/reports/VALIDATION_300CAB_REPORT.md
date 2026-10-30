# 300-Cab Scenario Validation Benchmark Report

## Scale Parameters
- **Fleet Size:** 300 Vehicles (`CAB-0` to `CAB-299`)
- **Hub Infrastructure:** 1 Hub with 40 Chargers (`CHG-00` to `CHG-39`)
- **Assigned Trips:** 1,500 Trips (5 trips per vehicle)
- **Charging Sessions:** 300 Sessions (1 session per vehicle, staggered)
- **Validation Status:** 100% VALID (Zero invariant violations H1-H6)

## Timing & Latency
- **Warmup Iterations:** 5
- **Benchmark Iterations:** 20
- **Latency p50:** 3.290 ms
- **Latency p99:** 5.113 ms

## Hardware & Environment
- **OS:** macOS Darwin (arm64)
- **Processor:** Apple M4 (10 cores)
- **Memory:** 16 GB Unified Memory
- **JVM:** OpenJDK 21.0.13+9-LTS 64-Bit Server VM
