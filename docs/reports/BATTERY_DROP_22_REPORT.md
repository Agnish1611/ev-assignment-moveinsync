# Sudden drop from 41% to 22%

Numbers below are from one run of `BatteryDropTest` on 2026-10-31. The timer wraps `LadderReplanSolver.replan` only. The solver clock stays fixed at the reading, so the deadline flag is not the latency evidence. The wall time is.

This scene is three cabs and three trips. It is not the 300-cab solve, and that solve does not contain this replan.

## Published plan

Pack size is 60,000 Wh. The plan `plan-41` was built as if `CAB-A` still held 24,600 Wh, which is 41% of the pack.

| Cab | Planned SoC | Trip | Pickup (UTC) |
|---|---:|---|---|
| `CAB-A` | 24,600 Wh (41%) | `TRIP-NEXT` | 08:08:20 |
| `CAB-B` | 50,000 Wh | `TRIP-LOCKED` | 08:08:20 |
| `CAB-C` | 50,000 Wh | `TRIP-LATER` | 10:00:00 |

Each trip is 10 km and 1,200 s, with no slack past the planned dropoff. One hub, `HUB-1`, has four plugs and one 50 kW charger, `CHG-1`. Distance between distinct locations is 5,000 m at 10 m/s, so a deadhead is 500 s and 750 Wh. Consumption is 150 Wh/km. The horizon is 08:00–16:00Z. There is no tariff. Seed 11. Planning floor 18% (10,800 Wh). Lock window 900 s. Change budget 3. Change penalty 1,000,000 micro-units per changed assignment.

## Reading

At 2026-10-30T08:00:00Z, `CAB-A` reports 13,200 Wh, which is 22% of the pack. The pickup is 500 s later, and the deadhead is also 500 s, so the cab has to leave immediately. A charge does not fit before `TRIP-NEXT`.

The reading still clears the 15% validator floor and the 18% planning floor at that instant. It does not clear the trip:

- 13,200 Wh at the reading
- 750 Wh deadhead to the pickup leaves 12,450 Wh
- 1,500 Wh for the 10 km trip leaves 10,950 Wh
- 750 Wh reserve back to the hub leaves 10,200 Wh, under the 10,800 Wh planning floor

At the planned 24,600 Wh the same legs leave 21,600 Wh, so the published assignment was feasible and the reading is not.

## Result

- **Ladder wall time:** 17 ms
- **`TRIP-NEXT`:** `CAB-A` → `CAB-C`
- **`TRIP-LOCKED`:** stays on `CAB-B`
- **`TRIP-LATER`:** stays on `CAB-C`
- **Changed assignments:** 1
- **Unassigned:** 0
- **Charging sessions:** 0
- **Deadline exhausted:** false on the fixed clock
- **Validator:** valid

`CAB-B` is already on a pickup at the same instant, inside the lock window, and it is not the cab that drifted, so `TRIP-LOCKED` stays. `CAB-C` has 50,000 Wh and its other trip is at 10:00, so it takes `TRIP-NEXT` and keeps `TRIP-LATER`. No trip goes to backup. No session is inserted, because the broken assignment had no time to charge and the cab that received it did not need to.

## Hardware

- **OS:** macOS Darwin arm64
- **Processor:** Apple M4 (10 cores)
- **Memory:** 16 GB (17,179,869,184 bytes)
- **JVM:** OpenJDK 21.0.13+9-LTS (Temurin)
