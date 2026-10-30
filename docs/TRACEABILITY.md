# Requirement Traceability

Traceability between case study requirements and verification evidence.

| Requirement from case study | Evidence required | Status |
|---|---|---|
| Battery floor / charger capacity / trip timing | Validator + mutation tests + property tests | Phase 2 complete & verified: `PlanValidatorTest`, `PlanValidatorMutationTest` (24 mutations), `PlanValidatorPropertyTest` (1,000 jqwik trials), `PlanValidatorDeterminismTest` (200 permutations); jacoco line >= 90%, branch >= 80% |
| Minimise backups, then cost, with explanation | Objective tuple docs + tests + README trade-off section | Phase 4, 5, 7 |
| Re-plan within 2 s, minimal changes | Drift scenario test + latency metrics + replan_run table | `LadderReplanTest` moves the 22 percent trip and at most two others inside a 2 second deadline (D22). `LadderTriggerTest` covers an added trip, a cancellation, cab removal, and a cab below the floor (D26). `PlanPublicationIT` writes `replan_run` in the same transaction as the head (D27). Latency metrics remain Phase 12 |
| Charger failure re-plan | Charger-failure test | `ChargerTriggerTest` moves an active session off the failed plug. `TriggerReplanValidatorTest` checks that plan at 15 percent (D26) |
| Why unassigned (reasons) | Counterfactual probe tests + reason-code docs | `AssignmentDiagnosis` and `AssignmentDiagnosisTest`. Evidence is watt-hours and a UTC plug window (D25) |
| Per-cab timeline with SoC at each step | Timeline endpoint + tests | Generator: `CabTimeline` and `CabTimelineTest`. HTTP endpoint remains Phase 11 |
| Heuristic + complexity + lower bound | Javadoc Big-O + lower-bound report in `docs/reports/` | Phase 5, 6, 7 |
| Independent validator run on every result | ArchUnit isolation test + validator invoked in publish path | `ValidatorArchitectureTest` keeps the validator off the planner. `PlanPublication` stores a head only when `PlanValidator` accepts the plan (D27). HTTP publish remains Phase 11 |
| 300-cab case in < 30 s with report | Benchmark test + committed report | Planner solve: `BenchmarkScenarioTest` and `docs/reports/PLANNER_300CAB_REPORT.md`. Validator latency: `docs/reports/VALIDATION_300CAB_REPORT.md` |
| Concurrency + failure tests | Named tests listed in README | `PlanPublicationIT` runs two writers on one shard: one head advances, the other conflicts (D27). Kafka failure tests remain Phase 10. The drift storm remains Phase 13 |
| Throughput and p50/p99 | k6/JMH output in README with machine specs | Phase 13 |
| One-command run | CI job doing `docker compose up` smoke test | Phase 0, 14 |
