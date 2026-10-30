# EV Fleet Charging & Trip Assignment Service

Production-grade EV fleet charging and trip assignment optimization platform for MoveInSync Case Study 6 (Advanced Track).

## Run

```bash
docker compose up --build
```

That starts two application instances, Postgres, Kafka in KRaft mode, Schema Registry, Keycloak, Prometheus, and Grafana. Passwords in `docker-compose.yml` and the Keycloak realm are dev-only. The application connects as `ev_api`, which is not a superuser. Flyway runs as `ev_admin` before that connection opens.

The compose broker uses replication factor 1. Production is replication factor 3 with `min.insync.replicas=2`.

| Port | What |
|---|---|
| 8080 | First application instance |
| 8081 | Second application instance |
| 8180 | Keycloak realm `ev` |
| 8085 | Schema Registry |
| 3001 | Grafana, user `admin`, password `admin`. Anonymous viewers can open the dashboard |
| 9090 | Prometheus |
| 5433 | Postgres, migration user `ev_admin`, application user `ev_api`, password `ev_password` |

Keycloak users `operator`, `employee`, `admin`, and `device` each use that same word as the password. All four are in tenant `tenant-a`. The client is `ev-api`. A walkthrough is [docs/DEMO.md](docs/DEMO.md). The design note is [docs/DESIGN_NOTE.md](docs/DESIGN_NOTE.md). A Postman collection is `deploy/postman/ev-fleet.postman_collection.json`.

## Assumptions

- Backup petrol and diesel cabs are unlimited. A trip the electric fleet cannot cover is reported as demand, with a reason.
- A cab belongs to one shift plan at a time.
- A vehicle has a maximum charge-acceptance power. Plug power is the minimum of that and the charger.
- A charging session is one contiguous interval.
- Employees are opaque ids. The tenant and the role come from the token claims `tenant_id` and `role`.
- Energy is whole watt-hours, distance is metres, and time is UTC epoch seconds. The planner keeps an 18 percent margin. The validator enforces the 15 percent floor.
- The 300-cab run is a generated evening fleet of that size. Every trip fits, so the backup count is 0 and matches the lower bound in `docs/reports/PLANNER_300CAB_REPORT.md`. The case study's illustration of 1,462 covered trips and 38 backups is a tighter demand pattern than this generated fleet. `AssignmentDiagnosis` explains a trip when the fleet cannot cover it. `LadderReplanTest` is the cab that arrives at 22 percent instead of 41 percent.

## Architecture & Modules

The platform is designed in strict accordance with [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md):

- **`ev-domain`**: Immutable domain value objects and domain logic (pure Java, zero framework/database dependencies).
- **`ev-validator`**: Independent validation engine enforcing hard invariants H1–H6 against ground-truth `ValidationInput`. Depends **only** on `ev-domain`.
- **`ev-planner-core`**: Optimization engine (ALNS, flow re-timer, lower bounds, counterfactual diagnosis) in pure Java.
- **`ev-service`**: Spring Boot REST application, Kafka event streaming, persistence with PostgreSQL RLS, and Keycloak RBAC.
- **`ev-sim`**: Deterministic scenario generation, battery drift injection, and load driving.
- **`ev-bench`**: JMH micro-benchmarks and k6 performance test assets.

## Non-Negotiable Invariants

All plans must satisfy invariants H1–H6 prior to publication or persistence:

| ID | Invariant | Description | Enforced By |
|:---|:---|:---|:---|
| **H1** | Battery Floor | State of Charge never drops below 15% (or configured floor) at any second, including deadhead | `ev-validator` + mutation/property tests |
| **H2** | Charger Capacity | Concurrent charging sessions at any hub never exceed charger count at any instant; physical plugs never double-booked | `ev-validator` + mutation/property tests |
| **H3** | Trip Timing | Assigned trips start on time and complete before latest allowed finish deadline | `ev-validator` + mutation/property tests |
| **H4** | Assignment Exclusivity | Each trip is assigned to at most one cab; cabs perform at most one activity at a time | `ev-validator` + mutation/property tests |
| **H5** | Reserve to Hub | Cab retains sufficient energy after dropoff to reach a designated charger above the battery floor | `ev-validator` + mutation/property tests |
| **H6** | Charging Profile | Session duration and delivered energy match the two-segment non-linear curve (fast to 80%, slow above) | `ev-validator` + mutation/property tests |

### Numeric Correctness & Integer Math
- **Integer arithmetic exclusively**: Energy in Wh, distance in metres, time in UTC epoch seconds, monetary costs in integer micro-units.
- Floating-point arithmetic (`float`, `double`) is strictly forbidden across `ev-domain`, `ev-planner-core` feasibility checks, and `ev-validator`.
- Conservative rounding: Energy rounds UP, time rounds UP, capacity rounds DOWN.

### Deterministic Charging Curve Model (H6 & Decision D15)

The physical charging profile is governed by a deterministic two-segment piecewise-linear model configured via typed `ChargingCurveConfig(fastThresholdPercent, slowRatePercent)` (defaults: 80% threshold, 50% power ratio):

1. **Fast Segment ($0\% \to 80\%$ SoC):**
   - Operates at 100% of effective plug power: $P_{\text{fast}} = \min(P_{\text{charger}}, P_{\text{vehicle}}) \times 1000\text{ W}$.
   - Duration required to charge $E_{\text{fast}}$ Wh:
     $$\Delta t_{\text{fast}} = \left\lceil \frac{E_{\text{fast}} \times 3600}{P_{\text{fast}}} \right\rceil\text{ seconds}$$
   - Energy delivered in duration $t \le \Delta t_{\text{thresh}}$:
     $$\Delta E_{\text{delivered}} = \left\lfloor \frac{t \times P_{\text{fast}}}{3600} \right\rfloor\text{ Wh}$$

2. **Slow Segment ($80\% \to 100\%$ SoC):**
   - Tapered charging rate at 50% effective plug power: $P_{\text{slow}} = \lfloor P_{\text{fast}} \times 50 / 100 \rfloor\text{ W}$.
   - Duration required to charge $E_{\text{slow}}$ Wh:
     $$\Delta t_{\text{slow}} = \left\lceil \frac{E_{\text{slow}} \times 3600}{P_{\text{slow}}} \right\rceil\text{ seconds}$$
   - Energy delivered in remaining duration $t_{\text{rem}} = t - \Delta t_{\text{thresh}}$:
     $$\Delta E_{\text{delivered}} = \left\lfloor \frac{t_{\text{rem}} \times P_{\text{slow}}}{3600} \right\rfloor\text{ Wh}$$

3. **Conservative Rounding Invariant:**
   - Charging session durations round **UP** to avoid underestimating charger plug occupancy.
   - Energy delivery rounds **DOWN** to avoid overestimating vehicle battery SoC.
   - Energy conservation strictly verified: claimed session target SoC must match curve delivery for the elapsed duration (`RuleId.CLAIMED_SOC_MISMATCH`).

## Trade-offs

Plan writes prefer consistency. A version becomes the head only after the independent validator accepts it, and only if the compare-and-set still sees the expected version. A failed write leaves the previous head. Reads stay available from the cache for the lifetimes in decision D30.

A plan with fewer backup trips beats any plan with more, whatever the charging bill. Among plans with the same backup count, the cheaper charging plan wins. The flow re-timer may move a session onto a cheaper tariff after the assignments are frozen. A re-plan also pays a change penalty, so a small saving that moves many assignments loses. The ladder stops when the clock reaches the deadline and keeps the last validator-clean plan.

[docs/DESIGN_NOTE.md](docs/DESIGN_NOTE.md) records what this build leaves out, including the live failover of one web instance and the fact that those instances do not run the solver.

## Caching

A published plan version is cached by tenant, shard, and version. It has no expiry. The cache is size-bounded. The current head expires after 2 seconds and is dropped when a plan-published event arrives. Fleet rows expire after 60 seconds and are dropped when a config event for that tenant arrives. Base distance is cached for 24 hours. Traffic is not cached. A missed event is bounded by those lifetimes. Decision D30 records the sizes.

## Proof

| What the case study asks for | Where it is proved |
|---|---|
| Battery floor, charger capacity, trip timing | `PlanValidatorTest`, `PlanValidatorMutationTest`, `PlanValidatorPropertyTest` |
| 22 percent drift within 2 seconds, at most two other changes | `LadderReplanTest` |
| Charger failure | `ChargerTriggerTest` |
| Why a trip is unassigned | `AssignmentDiagnosisTest` |
| Per-cab timeline with charge at each step | `CabTimelineTest`, `HttpAccessIT` |
| 300 cabs, 40 chargers, 1,500 trips in under 30 seconds | `BenchmarkScenarioTest`, `docs/reports/PLANNER_300CAB_REPORT.md`, `docs/reports/VALIDATION_300CAB_REPORT.md` |
| Two writers on one shard | `PlanPublicationIT.twoWritersCannotBothAdvanceTheHead` |
| Kill mid-solve, duplicate, reorder, poison, dropped database | `WorkerCrashTest`, `DeliveryTest`, `ConnectionDropIT` |
| One hundred cabs drifting together | `DriftStormTest` |
| Tenant isolation and roles | `PlanTenancyIT`, `HttpAccessIT` |

## Verification Gate

The repository enforces a zero-tolerance verification gate via `scripts/verify.sh`:

1. **Spotless**: Google Java Format compliance.
2. **Maven Verify**: Strict compilation (`-Xlint:all -Werror`), Checkstyle, SpotBugs, ArchUnit architectural isolation, and JaCoCo coverage checks.
3. **Forbidden Constructs Grep**: Zero `System.out`/`System.err`, zero `catch (Throwable)` or empty catch blocks, zero `Thread.sleep` in production, zero `float`/`double` in domain/validator/planner, zero `@Disabled` or `@Ignore` tests, zero unchecked non-deterministic clocks.
4. **Log & PII Hygiene**: Grep checks to guarantee zero credentials or PII patterns in code and logs.
5. **Integration Tests**: Testcontainers environment verification.
6. **Release Candidate Status**: Release benchmarking triggers.

### Running Verification Locally

```bash
# Execute the full quality gate
./scripts/verify.sh
```

## Performance

Measured on an Apple M4 (10 cores, 16 GB, Darwin arm64) with Temurin 21.0.13+9-LTS. The full write-up is `docs/reports/PROOF_LATENCY.md`. The 300-cab construction remains 24.258 s (`docs/reports/PLANNER_300CAB_REPORT.md`).

| Run | Result |
|---|---|
| Construction, 10 / 20 / 50 / 100 cabs | 18 ms, 27 ms, 164 ms, 1,012 ms. Each plan passed the validator |
| JMH charge duration across the 80 percent knee | 1.908 ± 0.132 ns/op |
| k6, 10 users, 15 s, `GET /actuator/health` | 12,401.5 req/s, p50 684 µs, p99 1.7 ms, 186,023 responses, none failed |

Construction is O(T² · C · H · P) time and O(T + C) space for T trips, C cabs, H hubs, and P plugs. The lower bound is O(T · C · H + T log T) time and O(T) space. A single construction stays inside the 30 second budget at the case-study size of 300 cabs, 40 chargers, and 1,500 trips. That run already uses 24.258 s, and this build has no measured fleet larger than that. That is where one construction stops fitting the budget on this machine. The k6 figure is the public health scrape. It is not a plan publish. The validator on that same 300-cab plan is 3.290 ms at p50 and 5.113 ms at p99 (`docs/reports/VALIDATION_300CAB_REPORT.md`).
