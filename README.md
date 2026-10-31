# EV fleet charging and trip assignment

Assigns a shift of trips to electric cabs, inserts charging stops on a two-segment curve, and publishes a plan only after an independent validator accepts it. This is the advanced track of MoveInSync case study 6.

## Run

```bash
docker compose up --build
```

That starts two web instances, one worker, Postgres, Kafka in KRaft mode, Schema Registry, Keycloak, Prometheus, and Grafana. Passwords in `docker-compose.yml` and the Keycloak realm are local defaults. The web instances connect as `ev_api`, which is not a superuser and cannot relay the outbox. The worker connects as `ev_worker`. Flyway runs as `ev_admin` on the web instances before those connections open. Topics are created before the processes start, and the broker does not auto-create them. A second worker uses the compose profile `second-worker` and the consumer group `ev-worker-2`.

The compose broker uses 6 partitions and replication factor 1. Production is replication factor 3 with `min.insync.replicas=2`.

| Port | What |
|---|---|
| 8080 | First application instance |
| 8081 | Second application instance |
| 8180 | Keycloak realm `ev` |
| 8085 | Schema Registry |
| 3001 | Grafana. User `admin`, password `admin`. Anonymous viewers can open the dashboard. |
| 9090 | Prometheus |
| 5433 | Postgres. Migration user `ev_admin`, web user `ev_api`, worker user `ev_worker`, password `ev_password`. |

Keycloak users `operator`, `employee`, `admin`, and `device` each use that same word as the password. All four are in tenant `tenant-a`. The client is `ev-api`. The walkthrough is [docs/DEMO.md](docs/DEMO.md). The short design note is [docs/DESIGN_NOTE.md](docs/DESIGN_NOTE.md). The full design is [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md). A Postman collection is `deploy/postman/ev-fleet.postman_collection.json`.

## Assumptions

- Backup petrol and diesel cabs are unlimited. A trip the electric fleet cannot cover is reported as demand, with a reason.
- A cab belongs to one shift plan at a time.
- Plug power is the minimum of the charger and the vehicle's maximum charge acceptance.
- A charging session is one contiguous interval. Fast to 80 percent of the pack, half power above that. Duration rounds up. Energy delivered rounds down.
- Employees are opaque ids. The tenant and the role come from the token claims `tenant_id` and `role`.
- Energy is whole watt-hours, distance is metres, and time is UTC epoch seconds. The planner keeps an 18 percent margin. The validator enforces the 15 percent floor.
- The 300-cab run is a generated evening fleet. Every trip fits, so the backup count is 0 and matches the lower bound in `docs/reports/PLANNER_300CAB_REPORT.md`. The case study's illustration of 1,462 covered trips and 38 backups is a tighter demand pattern than this fleet. `AssignmentDiagnosis` explains a trip the fleet cannot cover. `LadderReplanTest` is the cab that arrives at 22 percent instead of 41 percent.

## Modules

- `ev-domain` holds the integer values. It has no framework and no database.
- `ev-validator` checks invariants H1–H6 against ground truth. It depends only on `ev-domain`.
- `ev-planner-core` builds and repairs plans: regret-2 construction, ALNS, the flow re-timer, lower bounds, and the unassigned-trip diagnosis.
- `ev-service` is the Spring Boot process: HTTP, Kafka, PostgreSQL row-level security, and Keycloak.
- `ev-sim` generates the fleets and the drift cases.
- `ev-bench` holds the JMH benchmark and the k6 script.

## Invariants

A plan is stored as the head only when the validator accepts it.

| Rule | What it protects |
|---|---|
| H1 | State of charge never goes below 15 percent, including the empty drive to a pickup. |
| H2 | Concurrent sessions at a hub never exceed its charger count. |
| H3 | An assigned trip starts on time and finishes by its latest allowed finish. |
| H4 | A trip has one cab. A cab does one thing at a time. |
| H5 | After a trip the cab can still reach a charger above the floor. |
| H6 | Session length matches the two-segment curve. |

## Trade-off

A plan with fewer backup trips beats any plan with more, whatever the charging bill. Among plans with the same backup count, the cheaper charging plan wins. The flow re-timer may move a session onto a cheaper tariff after the assignments are frozen. A re-plan also pays a change penalty, so a small saving that moves many assignments loses. The ladder stops when the clock reaches the deadline and keeps the last validator-clean plan.

Writes prefer a single head. A version becomes current only after the validator accepts it and the compare-and-set still sees the expected version. A failed write leaves the previous head. Reads stay available from the cache for the lifetimes in [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md). The two web instances serve that head. They do not run the solver. [docs/DESIGN_NOTE.md](docs/DESIGN_NOTE.md) lists what was left out.

## Caching

A published plan version is cached by tenant, shard, and version. It has no expiry. The cache is size-bounded. The current head expires after 2 seconds and is dropped when a plan-published event arrives. Fleet rows expire after 60 seconds and are dropped when a config event for that tenant arrives. Base distance is cached for 24 hours. Traffic is not cached. A missed event is bounded by those lifetimes.

## Where each requirement is proved

| Requirement | Test or report |
|---|---|
| Battery floor, charger capacity, trip timing | `PlanValidatorTest`, `PlanValidatorMutationTest`, `PlanValidatorPropertyTest` |
| 22 percent drift within 2 seconds, at most two other changes | `LadderReplanTest` |
| Charger failure | `ChargerTriggerTest` |
| Why a trip is unassigned | `AssignmentDiagnosisTest` |
| Per-cab timeline with charge at each step | `CabTimelineTest`, `HttpAccessIT` |
| 300 cabs, 40 chargers, 1,500 trips in under 30 seconds | `BenchmarkScenarioTest`, `docs/reports/PLANNER_300CAB_REPORT.md`, `docs/reports/VALIDATION_300CAB_REPORT.md` |
| Two writers on one shard | `PlanPublicationIT.twoWritersCannotBothAdvanceTheHead` |
| Kill mid-solve, duplicate, reorder, poison, dropped database, paused broker, paused worker | `WorkerCrashTest`, `SolveKillTest`, `CommitKillTest`, `DeliveryTest`, `ConnectionDropIT`, `StoreOutageIT`, `KafkaOutageIT`, `ZombieWorkerIT` |
| One hundred cabs drifting together | `DriftStormTest` |
| Tenant isolation and roles | `PlanTenancyIT`, `HttpAccessIT` |

`scripts/verify.sh` runs formatting, the Maven verify (compiler warnings as errors, Checkstyle, SpotBugs, ArchUnit, JaCoCo), the forbidden-construct scan, the log and PII scan, and the Testcontainers tests.

## Performance

Measured on an Apple M4 (10 cores, 16 GB, Darwin arm64) with Temurin 21.0.13+9-LTS. The write-up is `docs/reports/PROOF_LATENCY.md`. The 300-cab construction is 2.710 s (`docs/reports/PLANNER_300CAB_REPORT.md`).

| Run | Result |
|---|---|
| Construction, 10 / 20 / 50 / 100 cabs | 15 ms, 8 ms, 51 ms, 100 ms. Each plan passed the validator. |
| JMH charge duration across the 80 percent knee | 1.908 ± 0.132 ns/op |
| k6, 10 users, 15 s, `GET /actuator/health` | 12,401.5 req/s, p50 684.12 µs, p99 1.7 ms, 186,023 responses, none failed |

Construction is O(T² · C · H · P) time when every accepted trip books a plug, and O(T · C) space for the regret table. A commit that does not book a plug re-probes only that cab. T is trips, C is cabs, H is hubs, and P is plugs. The lower bound is O(T · C · H + T log T) time and O(T) space. A single construction of the case-study fleet, 300 cabs, 40 chargers, and 1,500 trips, took 2.710 s. No larger fleet was measured. Worst-case time still applies when every accepted trip books a plug. This fleet does not. The published k6 figure was taken before readiness checked the database. It is not a plan publish. The validator on that same 300-cab plan is 3.290 ms at p50 and 5.113 ms at p99 (`docs/reports/VALIDATION_300CAB_REPORT.md`).
