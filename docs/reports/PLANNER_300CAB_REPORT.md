# 300-Cab Planner Solve

Numbers below are from one run of `BenchmarkScenarioTest` on 2026-10-31. The fleet is `PricedScale`. The solve timer wraps `GreedyConstructionSolver.solve` only. The solver clock stays fixed, so the assignments do not depend on the machine. This run does not replan.

`ScaleScenario.fleet` is a different generator: 50 kWh at the start, 2 km trips, one hub, and no tariff. The scale curve still uses that generator. It was not this measurement.

## Scale

- **Cabs:** 300 (`CAB-000` to `CAB-299`), 60,000 Wh pack, start SoC 28,000 Wh, available at 2026-10-30T08:00:00Z, rotated across the three depots
- **Chargers:** 40 at 50 kW. `HUB-1` has 14 (`CHG-00` to `CHG-13`), `HUB-2` has 13, `HUB-3` has 13
- **Tariffs, each hub, UTC on 2026-10-30:** 08:00–14:00 at 20,000,000 micro-units/kWh, 14:00–20:00 at 4,000,000 micro-units/kWh
- **Horizon:** 08:00–20:00Z
- **Distance:** 5,000 m and 500 s between distinct locations, 150 Wh/km, constant consumption
- **Trips:** 1,500

| Trips | Pickup (UTC) | Distance | Duration | Slack |
|---|---|---:|---:|---:|
| `TRIP-0000`–`TRIP-0449` (450) | 09:00 | 2 km | 600 s | 0 |
| `TRIP-0450`–`TRIP-0749` (300) | 10:20 | 2 km | 600 s | 0 |
| `TRIP-0750`–`TRIP-1049` (300) | 11:40 | 2 km | 600 s | 0 |
| `TRIP-1050`–`TRIP-1349` (300) | 13:00 | 2 km | 600 s | 0 |
| `TRIP-1350`–`TRIP-1353` (4) | 14:50 | 80 km | 3,000 s | 600 s |
| `TRIP-1354`–`TRIP-1499` (146) | 15:20 | 2 km | 600 s | 0 |

- **Solver:** regret-2 construction, seed 11, planning floor 18% (10,800 Wh), no ALNS iterations
- **Solver clock:** fixed at 2026-10-30T08:00:00Z, deadline 30 s later on that clock

## Result

- **Solve time:** 24.870 s
- **Assigned:** 1,350
- **Unassigned:** 150
- **Charging sessions:** 4
- **Charging cost:** 20,000,000 micro-units
- **Deadline exhausted:** false
- **Validator:** valid at the 15% floor with reserve-to-hub enforced

The test fails the run at 30 s. This sample is one measurement on the machine below.

## Backups

450 pickups share 09:00 and the fleet has 300 cabs, so at least 150 trips have no cab. The overlap lower bound is 150. Intrinsic backups are 0 and energy backups are 0. The run left 150 trips unassigned and assigned every later trip.

## Charging

An 80 km trip needs 24,300 Wh on board before its deadhead: the 18% floor (10,800 Wh), the 12,000 Wh trip, and 750 Wh each way to a hub. After four 2 km hops the cab is under that, so the construction inserts a charge. The same energy at the peak rate would cost 100,000,000 micro-units. The published sessions start at 14:00, the first instant of the off-peak window, and the cost matches the off-peak rate on 1,250 Wh: `1,250 * 4,000,000 / 1,000 = 5,000,000` each.

Equal prices tie-break to the earlier start, then the smaller hub id, then the smaller charger id. All four sessions are on `HUB-1`.

| Cab | Start (UTC) | End (UTC) | Plug | Start Wh | End Wh | Cost (micro-units) |
|---|---|---|---|---:|---:|---:|
| `CAB-000` | 14:00:00 | 14:01:30 | `CHG-00` | 23,050 | 24,300 | 5,000,000 |
| `CAB-001` | 14:00:00 | 14:01:30 | `CHG-01` | 23,050 | 24,300 | 5,000,000 |
| `CAB-002` | 14:00:00 | 14:01:30 | `CHG-02` | 23,050 | 24,300 | 5,000,000 |
| `CAB-003` | 14:00:00 | 14:01:30 | `CHG-03` | 23,050 | 24,300 | 5,000,000 |

`CAB-000` does a morning hop, then the charge, then the 80 km trip. Dropoff SoC is the watt-hours recorded on that assignment. The charge starts 750 Wh lower, which is the 5 km deadhead from the 13:10 dropoff into the hub.

| Step | When (UTC) | What | SoC after (Wh) |
|---|---|---|---:|
| Start | 08:00 | at the depot | 28,000 |
| `TRIP-0000` | 09:00–09:10 | 2 km | 26,950 |
| `TRIP-0450` | 10:20–10:30 | 2 km | 25,900 |
| `TRIP-0750` | 11:40–11:50 | 2 km | 24,850 |
| `TRIP-1050` | 13:00–13:10 | 2 km | 23,800 |
| `CHG-00` | 14:00:00–14:01:30 | 1,250 Wh at the off-peak rate | 24,300 |
| `TRIP-1350` | 14:50–15:40 | 80 km | 11,550 |

The 13:10 dropoff plus the 500 s deadhead reaches the hub at 13:18:20, still inside the peak window. The 80 km pickup is 14:50, so the cab has to leave the hub by 14:41:40. 14:00 sits in that gap and is five times cheaper than charging at 13:18.

## Lower bound

| Component | Value |
|---|---|
| Intrinsic backups | 0 |
| Overlap backups | 150 |
| Energy backups | 0 |
| Backup lower bound | 150 |
| Achieved backups | 150 |
| Flow relaxation | 20,000,000 micro-units |
| Achieved charging cost | 20,000,000 micro-units |
| Tariff floor of the energy pack | 0 micro-units |

The tariff floor prices only the grid energy that remains after starting batteries and charger throughput, and it omits deadhead. On this fleet that remainder is 0, so the floor is 0. The flow relaxation is the preemptive charging cost of the plan that was built, and it matches the 20,000,000 micro-units actually charged.

## Hardware

- **OS:** macOS Darwin arm64
- **Processor:** Apple M4 (10 cores)
- **Memory:** 16 GB (17,179,869,184 bytes)
- **JVM:** OpenJDK 21.0.13+9-LTS (Temurin)
