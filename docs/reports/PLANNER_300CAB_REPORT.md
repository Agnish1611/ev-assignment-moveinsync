# 300-Cab Planner Solve

Numbers below are from one run of `BenchmarkScenarioTest` on 2026-10-30. The solve timer wraps `GreedyConstructionSolver.solve` only.

## Scale

- **Cabs:** 300 (`CAB-000` to `CAB-299`), 60 kWh, start SoC 50 kWh, at the hub
- **Chargers:** 40 (`CHG-00` to `CHG-39`) on `HUB-1`
- **Trips:** 1,500 (`TRIP-0000` to `TRIP-1499`), five shared pickup slots, 2 km and 600 s each
- **Solver:** regret-2 construction, seed 11, planning floor 18%, no ALNS iterations
- **Solver clock:** fixed at 2026-10-30T08:00:00Z, deadline 30 s later

## Result

- **Solve time:** 24.258 s
- **Assigned:** 1,500
- **Unassigned:** 0
- **Deadline exhausted:** false
- **Validator:** valid at the 15% floor with reserve-to-hub enforced

## Lower bound

| Component | Value |
|---|---|
| Intrinsic backups | 0 |
| Overlap backups | 0 |
| Energy backups | 0 |
| Backup lower bound | 0 |
| Achieved backups | 0 |
| Flow relaxation | 0 micro-units |
| Achieved charging cost | 0 micro-units |
| Tariff floor of the energy pack | 0 micro-units |

Backups are at least 0 and the run achieved 0. The preemptive flow relaxation is 0 and the published charging cost is 0.

## Hardware

- **OS:** macOS Darwin arm64
- **Processor:** Apple M4 (10 cores)
- **Memory:** 16 GB (17,179,869,184 bytes)
- **JVM:** OpenJDK 21.0.13+9-LTS (Temurin)
