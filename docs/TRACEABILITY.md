# Requirement traceability

Each row is a case-study requirement and the test or report that shows it.

| Requirement from case study | Evidence required | Status |
|---|---|---|
| Battery floor, charger capacity, trip timing | Validator, mutation, and property tests | `PlanValidatorTest`, `PlanValidatorMutationTest` (24 mutations), `PlanValidatorPropertyTest` (1,000 trials), `PlanValidatorDeterminismTest` (200 permutations). JaCoCo line coverage at least 90 percent and branch coverage at least 80 percent. |
| Fewer backups first, then cost, with an explanation | Objective and the trade-off write-up | README trade-off section and `docs/DESIGN_NOTE.md`. Fewer backups, then the change penalty plus charging cost. |
| Re-plan within 2 seconds, few changes | Drift scenario, latency, `replan_run` | `LadderReplanTest` moves the 22 percent trip and at most two others inside 2 seconds. `LadderTriggerTest` covers an added trip, a cancellation, cab removal, and a cab below the floor. `PlanPublicationIT` writes `replan_run` in the same transaction as the head. `DriftStormTest` applies 100 readings inside 2 seconds and the validator accepts the plan. `ServiceMeters` records `ev.replan.latency` by tier. |
| Charger failure | Re-plan off the failed plug | `ChargerTriggerTest` moves the active session. `TriggerReplanValidatorTest` checks that plan at 15 percent. |
| Why a trip is unassigned | Counterfactual probe | `AssignmentDiagnosisTest`. Evidence is watt-hours and a UTC plug window. `ServiceMeters` counts each reason code. |
| Per-cab timeline with charge at each step | Timeline read | `CabTimelineTest`. `HttpAccessIT` reads `GET /v1/cabs/{id}/timeline`. Version reads are cached by tenant, shard, and version. |
| Heuristic, complexity, lower bound | Javadoc and the 300-cab report | Regret-2 construction is O(T² · C · H · P) time and O(T + C) space (`GreedyConstructionSolver`). `FleetLowerBound` is O(T · C · H + T log T) time and O(T) space. The 300-cab run achieved 0 backups against a lower bound of 0 (`docs/reports/PLANNER_300CAB_REPORT.md`). |
| Independent validator on every result | Isolation and the publish path | `ValidatorArchitectureTest`. `PlanPublication` stores a head only when `PlanValidator` accepts the plan. `POST /v1/plans` records a job and does not publish. |
| 300 cabs in under 30 seconds, with a report | Benchmark and committed reports | `BenchmarkScenarioTest`, `docs/reports/PLANNER_300CAB_REPORT.md`, `docs/reports/VALIDATION_300CAB_REPORT.md`. |
| Concurrency and failure | Named tests | `PlanPublicationIT.twoWritersCannotBothAdvanceTheHead`. `DeliveryTest` drops a duplicate, drops an older sequence, and dead-letters a poison payload. `WorkerCrashTest` kills a solve before the sequence is committed. `ConnectionDropIT` leaves the head on the committed version. `OutboxRelayIT` publishes the outbox once. `HttpAccessIT` checks tenants and roles. `DriftStormTest` is the 100-cab storm. |
| Throughput, p50, p99, and the machine | k6, JMH, and the scale curve | `docs/reports/PROOF_LATENCY.md`. k6: 12,401.5 req/s, p50 684.12 µs, p99 1.7 ms on health. JMH: 1.908 ± 0.132 ns/op. Scale curve 18 ms, 27 ms, 164 ms, 1,012 ms at 1x, 2x, 5x, 10x. Apple M4, 10 cores, 16 GB, Temurin 21.0.13. The 300-cab solve is 24.258 s. |
| One command | Compose | `docker compose up --build` starts two app instances, Postgres, Kafka in KRaft mode, Schema Registry, Keycloak realm `ev`, Prometheus, and Grafana. The CI workflow runs `docker compose up -d`, checks `docker compose ps`, and tears the stack down. |
