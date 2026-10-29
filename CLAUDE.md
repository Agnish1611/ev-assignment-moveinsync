# CLAUDE.md

@AGENTS.md
@docs/ARCHITECTURE.md

The two files imported above are binding. Everything in `AGENTS.md` applies to you in full. This file adds
Claude Code specific working rules and does not override anything in `AGENTS.md`. If they ever appear to conflict,
`AGENTS.md` wins and you must stop and tell the human.

## Session start checklist (do this every session, before touching code)

1. Read `AGENTS.md` and `docs/ARCHITECTURE.md` (imported above), then `docs/DECISIONS.md` and `docs/TRACEABILITY.md`.
2. Run `git status` and `git log --oneline -15` to see where the previous session stopped.
3. Run `scripts/verify.sh`. If it is red before you changed anything, report that first and fix it before new work.
4. State in one short paragraph: which phase you are in, what the next task is, and which invariants it touches.

## Workflow for every task

1. **Plan first.** For anything touching more than one file or any hard rule (H1-H6), write a short plan before editing:
   files to change, tests to add, invariants affected. For core algorithm or validator work, wait for human approval
   of the plan.
2. **Tests first or alongside.** Write the failing test, see it fail for the right reason, then implement.
3. **Smallest correct change.** No drive-by refactors, no renaming unrelated code, no formatting-only noise mixed
   into functional commits.
4. **Run the gate.** `scripts/verify.sh` must be green before you say "done" or commit.
5. **Commit.** One logical change, Conventional Commit message, tests included in the same commit as the code.
6. **Report honestly.** List exactly what you ran and the outcome. If something was not run or failed, say so
   plainly. Never write "should work" or "tests pass" about anything you did not run.

## Things you must never do (even if asked casually, even to save time)

- Weaken, skip, delete or `@Disabled` a test; widen a tolerance; or loosen a validator check to get green.
- Make `ev-validator` import from `ev-planner-core` or share code with it. The validator's independence is the
  central correctness guarantee of this project.
- Publish or persist a plan as head without a passing validator report.
- Use `double`/`float` in constraint logic, put personal data in logs or errors, or take the tenant from request input.
- Edit protected files listed in `AGENTS.md` section 10 without explicit approval in the current conversation.
- Add `@SuppressWarnings`, static-analysis exclusions, lowered coverage thresholds, or `--no-verify` to any command.
- Rewrite git history, force-push, or squash commits.
- Fabricate benchmark numbers, validator reports, or test results. Every number in the README and `docs/reports/`
  must come from a run you actually performed, with the machine specs recorded.

## If you are tempted to cut a corner

That is the signal to stop and ask. Say what you are blocked on, what options you see, and what each costs. A
paused task is acceptable; a silently relaxed rule is not.

## Useful commands

```bash
scripts/verify.sh                                   # the single quality gate
./mvnw -q -pl ev-validator -am test                 # validator + domain only
./mvnw -q -pl ev-planner-core -am test              # planner core, no Spring needed
./mvnw -q -pl ev-service -am verify                 # service with integration tests
docker compose up --build                           # full stack
```

## Review checklist before you present work

- [ ] `scripts/verify.sh` green, output pasted
- [ ] New logic has unit tests plus edge cases; hard-rule changes have mutation or property tests
- [ ] No forbidden constructs, no suppressions, no new warnings
- [ ] No PII in logs, errors, fixtures or metrics labels
- [ ] Big-O Javadoc on any new core algorithm
- [ ] `docs/TRACEABILITY.md` and `docs/DECISIONS.md` updated if relevant
- [ ] Commit is small, conventional, and contains its tests