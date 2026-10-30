# History options for `a1c9be4`

Chosen: option B. `AGENTS.md` forbids rewriting history, and the branch tip already passes `scripts/verify.sh`. Rewriting `a1c9be4` would conflict with `8cf24bd` and would not change the code reviewers run. `a1c9be4` remains a known broken commit: `scripts/verify.sh` fails there at `testCompile`. Bisect across that commit is not meaningful. Option A is recorded below and was not applied.

`git rebase -x scripts/verify.sh d6a23c9` was run on throwaway branch `tmp-rebase-check` (worktree, then deleted). It stopped on the first picked commit, `a1c9be4`. Spotless passed. `ev-validator` `testCompile` failed. Later commits were not replayed. `git rebase --abort` was run, and the throwaway branch and worktree were removed.

## What `a1c9be4` changed

Production-only commit, parent `d6a23c9`. It removed `PlanValidator(ValidationContext)` and `ValidationReport validate(FleetPlan)`. Tests still called both. It also changed `ValidationInput.builder()` so the return type is `ValidationInputBuilder`, while two tests still declared `ValidationInput.Builder`.

Compiler failures observed at that commit:

- `PlanValidatorTest` and `PlanValidatorMutationTest`: `new PlanValidator(ValidationContext)` and `validate(FleetPlan)` do not resolve.
- `PlanValidatorDeterminismTest` and `ValidationPerformanceBenchmarkTest`: `ValidationInputBuilder` cannot be converted to `ValidationInput.Builder`.

## Signature history `d6779a0` through `d6a23c9`

At every commit in that range, both public validate methods existed, and `ValidationContext` had a public constructor. The earlier claim that only `validate(ValidationInput, FleetPlan)` existed in that range is false. `a1c9be4` is the commit that removed `validate(FleetPlan)`.

## Option A — fixup, not applied

```text
git switch -c fix/phase2-history-replay b027d24
git rebase -i d6a23c9
```

Mark `a1c9be4` as `edit`. At the stop, change tests only:

1. In `PlanValidatorTest` and `PlanValidatorMutationTest`, replace `new PlanValidator(context)` with `new PlanValidator()`, and replace `validator.validate(plan)` with `validator.validate(input, plan)` using the fixtures those tests already build. Leave assertions unchanged.
2. In `PlanValidatorDeterminismTest` and `ValidationPerformanceBenchmarkTest`, change the variable type from `ValidationInput.Builder` to `ValidationInputBuilder`.

Then `git add -u && git commit --amend --no-edit` and `git rebase --continue`.

Expect conflicts at `8cf24bd`, which deletes those two test files and splits them. Keep `8cf24bd`'s test tree for the deleted files. Do not squash `8cf24bd` into `a1c9be4`. After the replay, run `git rebase -x scripts/verify.sh d6a23c9` again and keep the result only if every commit passes.

## Option B — leave history, record the broken range

`a1c9be4` does not pass `scripts/verify.sh`. The range from `a1c9be4` until a later commit that compiles is a known broken range. `git bisect` across that commit is not meaningful. The branch tip is left as it is. This option does not rewrite history.
