# Requirement Traceability

Traceability between case study requirements and verification evidence.

| Requirement from case study | Evidence required | Status |
|---|---|---|
| Battery floor / charger capacity / trip timing | Validator + mutation tests + property tests | Phase 2 complete & verified: `PlanValidatorTest`, `PlanValidatorMutationTest` (24 mutations), `PlanValidatorPropertyTest` (1,000 jqwik trials), `PlanValidatorDeterminismTest` (200 permutations); jacoco line >= 90%, branch >= 80% |
| Minimise backups, then cost, with explanation | Objective tuple docs + tests + README trade-off section | Phase 4, 5, 7 |
| Re-plan within 2 s, minimal changes | Drift scenario test + latency metrics + replan_run table | Phase 8 |
| Charger failure re-plan | Charger-failure test | Phase 8 |
| Why unassigned (reasons) | Counterfactual probe tests + reason-code docs | Phase 7 |
| Per-cab timeline with SoC at each step | Timeline endpoint + tests | Phase 7, 11 |
| Heuristic + complexity + lower bound | Javadoc Big-O + lower-bound report in `docs/reports/` | Phase 5, 6, 7 |
| Independent validator run on every result | ArchUnit isolation test + validator invoked in publish path | Phase 0 & 2 complete & verified: `ValidatorArchitectureTest` (zero planner/framework imports), `ValidationInput` trust model, totality guard, zero warnings |
| 300-cab case in < 30 s with report | Benchmark test + committed report | Phase 13 |
| Concurrency + failure tests | Named tests listed in README | Phase 9, 10, 13 |
| Throughput and p50/p99 | k6/JMH output in README with machine specs | Phase 13 |
| One-command run | CI job doing `docker compose up` smoke test | Phase 0, 14 |
