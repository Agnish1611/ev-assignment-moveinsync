#!/usr/bin/env bash
# Quality gate. Grep checks fail closed: exit 0 from grep is a violation,
# exit 1 is a clean scan, and exit >= 2 (or a missing scan path) is a gate error.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
cd "${PROJECT_ROOT}"

# forbid MESSAGE REGEX [extra grep args...] -- PATH...
# Paths that do not exist are a gate error. They are never passed to grep:
# BSD grep reports a missing path as exit 1, which would look like a clean scan.
forbid() {
  local msg=$1
  local re=$2
  shift 2
  local -a grep_args=(-rnE --include='*.java' --exclude-dir=target --exclude-dir=.git)
  local -a paths=()
  local seen_dd=0
  local arg
  for arg in "$@"; do
    if [[ "$arg" == "--" ]]; then
      seen_dd=1
      continue
    fi
    if [[ "$seen_dd" -eq 0 ]]; then
      grep_args+=("$arg")
    else
      paths+=("$arg")
    fi
  done
  if [[ ${#paths[@]} -eq 0 ]]; then
    echo "GATE ERROR: forbid called with no paths: ${msg}"
    exit 2
  fi
  local path
  for path in "${paths[@]}"; do
    if [[ ! -e "$path" ]]; then
      echo "GATE ERROR: scan path does not exist: ${path}"
      exit 2
    fi
  done
  local out rc=0
  out=$(grep "${grep_args[@]}" -e "$re" "${paths[@]}" 2>&1) || rc=$?
  case $rc in
    0)
      printf '%s\n' "$out"
      echo "ERROR: ${msg}"
      exit 1
      ;;
    1)
      ;;
    *)
      printf '%s\n' "$out"
      echo "GATE ERROR: grep rc=${rc}"
      exit 2
      ;;
  esac
}

self_test_grep_gates() {
  SELF_TEST_TMP=$(mktemp -d "${TMPDIR:-/tmp}/verify-self.XXXXXX")
  local tmp="$SELF_TEST_TMP"
  cleanup_self() {
    if [[ -n "${SELF_TEST_TMP:-}" ]]; then
      chmod -R u+rwx "$SELF_TEST_TMP" 2>/dev/null || true
      rm -rf "$SELF_TEST_TMP"
    fi
  }
  trap cleanup_self EXIT

  local locked="${tmp}/locked"
  mkdir "$locked"
  chmod 000 "$locked"

  local clean="${tmp}/clean"
  mkdir "$clean"
  printf '%s\n' 'class Clean { String s = "defaultDistanceMeters must be non-negative"; void defaultCurve() {} }' \
    > "${clean}/Clean.java"

  expect_rc() {
    local want=$1
    shift
    local rc=0
    local out
    out=$("$@") || rc=$?
    if [[ "$rc" -ne "$want" ]]; then
      printf '%s\n' "$out"
      echo "SELF-TEST FAIL: expected rc=${want} got rc=${rc} for: $*"
      exit 1
    fi
  }

  local locked_rc=0
  grep -rnE --include='*.java' --exclude-dir=target -e 'nomatch' "$locked" >/dev/null 2>&1 || locked_rc=$?
  local use_locked=0
  if [[ "$locked_rc" -ge 2 ]]; then
    use_locked=1
  else
    echo "mode 000 is readable by this user (grep rc=${locked_rc}); each gate's grep error is an invalid option."
  fi

  local n=0
  check_one() {
    local msg=$1
    local re=$2
    local sample=$3
    shift 3
    n=$((n + 1))
    local hit="${tmp}/hit-${n}"
    mkdir "$hit"
    printf '%s\n' "$sample" > "${hit}/Bad.java"
    expect_rc 1 forbid "$msg" "$re" "$@" -- "$hit"
    if [[ "$use_locked" -eq 1 ]]; then
      expect_rc 2 forbid "$msg" "$re" "$@" -- "$locked"
    else
      expect_rc 2 forbid "$msg" "$re" "$@" --not-a-real-grep-option -- "$hit"
    fi
  }

  expect_rc 2 forbid 'missing path' 'nomatch' -- "${tmp}/does-not-exist"
  expect_rc 2 forbid 'bad regex' '[' -- "$clean"
  expect_rc 0 forbid 'sentinel tenant literal' '"default"([^A-Za-z0-9_]|$)' -- "$clean"

  check_one 'System.out or System.err' 'System\.(out|err)\.print' \
    'class B { void m(){ System.out.println("x"); } }'
  check_one 'printStackTrace' '\.printStackTrace\(\)' \
    'class B { void m(){ new Exception().printStackTrace(); } }'
  check_one 'wildcard import' \
    '^import[[:space:]]+static[[:space:]]+.*[a-zA-Z0-9_]+\.\*;|^import[[:space:]]+[a-zA-Z0-9_.]+\.\*;' \
    'import java.util.*;'
  check_one 'Thread.sleep' 'Thread\.sleep' \
    'class B { void m() throws Exception { Thread.sleep(1); } }'
  check_one 'catch Throwable' 'catch[[:space:]]*\([[:space:]]*Throwable' \
    'class B { void m(){ try {} catch (Throwable t) { throw t; } } }'
  check_one 'float or double' '\b(double|float)\b' \
    'class B { double x = 1; }'
  check_one 'Disabled or Ignore' '@(org\.junit(\.jupiter\.api)?\.)?(Disabled|Ignore)\b' \
    'class B { @Disabled void m() {} }'
  check_one 'empty catch' 'catch[[:space:]]*\([^)]+\)[[:space:]]*\{[[:space:]]*\}' \
    'class B { void m(){ try {} catch (Exception e) { } } }'
  check_one 'Instant.now or LocalDateTime' '\b(Instant\.now|LocalDateTime)\b' \
    'class B { void m(){ Instant.now(); } }'
  check_one 'TODO FIXME HACK' '\b(TODO|FIXME|HACK)\b' \
    'class B { void m(){ /* TODO later */ } }'
  check_one 'hard-coded credentials' \
    '(password|secret|apikey)[[:space:]]*=[[:space:]]*"[^"]+"' \
    'class B { String password = "x"; }' -i
  check_one 'PII logging' \
    'log\.(info|debug|warn|error).*([pP]hone|[eE]mail|[cC]oordinates)' \
    'class B { void m(){ log.info("phone={}", x); } }'
  check_one 'private key or token' \
    'BEGIN (RSA|EC|DSA|OPENSSH)? ?PRIVATE KEY|AKIA[0-9A-Z]{16}|ghp_[0-9a-zA-Z]{36}' \
    'class B { String k = "AKIA1234567890ABCDEF"; }' \
    --include='*.yml' --include='*.yaml' --include='*.properties' --include='*.xml'
  check_one 'sentinel tenant literal' '"default"([^A-Za-z0-9_]|$)' \
    'class B { String t = TenantId.of("default"); }'

  local probe_rc=0
  grep -rnE --include='*.java' --exclude-dir=target -e 'nomatch' "${tmp}/does-not-exist" >/dev/null 2>&1 || probe_rc=$?
  if [[ "$probe_rc" -ge 2 ]]; then
    echo "GNU grep missing-path probe rc=${probe_rc} (gate already rejects missing paths before grep)."
  else
    echo "BSD-style grep missing-path probe rc=${probe_rc}; missing paths are rejected before grep."
  fi
  if [[ "$use_locked" -eq 1 ]]; then
    echo "Grep gate self-test passed (${n} gates, violation rc=1 and unreadable-dir rc=2)."
  else
    echo "Grep gate self-test passed (${n} gates, violation rc=1 and invalid-option rc=2)."
  fi
}

if [[ "${1:-}" == "--self-test" ]]; then
  self_test_grep_gates
  exit 0
fi

SKIPPED_STEPS=0

if [ -d "${PROJECT_ROOT}/.jdk/jdk-21.0.13+9/Contents/Home" ]; then
  export JAVA_HOME="${PROJECT_ROOT}/.jdk/jdk-21.0.13+9/Contents/Home"
  export PATH="${JAVA_HOME}/bin:${PATH}"
fi

echo "================================================================"
echo " Running Quality Gate: scripts/verify.sh"
echo " Java version: $(java -version 2>&1 | head -n 1)"
echo " Working directory: ${PROJECT_ROOT}"
echo "================================================================"

echo "[Step 1/6] Running Spotless format check..."
./mvnw -q spotless:check
echo "✓ Spotless check passed."

echo "[Step 2/6] Running Maven build & test verification..."
./mvnw -q clean verify
echo "✓ Maven verify passed."

echo "[Step 3/6] Running forbidden construct checks..."

JAVA_TREE=(ev-domain ev-planner-core ev-validator ev-service ev-sim ev-bench)
MAIN_TREE=(
  ev-domain/src/main
  ev-planner-core/src/main
  ev-validator/src/main
  ev-service/src/main
  ev-sim/src/main
  ev-bench/src/main
)
PROD_NO_BENCH=(
  ev-domain/src/main
  ev-planner-core/src/main
  ev-validator/src/main
  ev-service/src/main
  ev-sim/src/main
)
CONSTRAINT_TREE=(ev-domain/src/main ev-planner-core/src/main ev-validator/src/main)
TIME_TREE=(ev-domain/src/main ev-validator/src/main)

forbid 'System.out or System.err detected in production/test code' \
  'System\.(out|err)\.print' -- "${JAVA_TREE[@]}"
forbid 'printStackTrace() detected in production/test code' \
  '\.printStackTrace\(\)' -- "${JAVA_TREE[@]}"
forbid 'Wildcard imports detected in production code' \
  '^import[[:space:]]+static[[:space:]]+.*[a-zA-Z0-9_]+\.\*;|^import[[:space:]]+[a-zA-Z0-9_.]+\.\*;' \
  -- "${MAIN_TREE[@]}"
forbid 'Thread.sleep detected in production code' \
  'Thread\.sleep' -- "${PROD_NO_BENCH[@]}"
forbid 'catch (Throwable) detected' \
  'catch[[:space:]]*\([[:space:]]*Throwable' -- "${JAVA_TREE[@]}"
forbid 'float or double type detected in constraint code' \
  '\b(double|float)\b' -- "${CONSTRAINT_TREE[@]}"
forbid '@Disabled or @Ignore detected in tests' \
  '@(org\.junit(\.jupiter\.api)?\.)?(Disabled|Ignore)\b' -- "${JAVA_TREE[@]}"
forbid 'Empty catch block detected' \
  'catch[[:space:]]*\([^)]+\)[[:space:]]*\{[[:space:]]*\}' -- "${JAVA_TREE[@]}"
forbid 'Instant.now() or LocalDateTime detected in ev-domain or ev-validator' \
  '\b(Instant\.now|LocalDateTime)\b' -- "${TIME_TREE[@]}"
forbid 'TODO/FIXME/HACK detected in codebase' \
  '\b(TODO|FIXME|HACK)\b' -- "${JAVA_TREE[@]}"
forbid 'Hard-coded credentials detected in production code' \
  '(password|secret|apikey)[[:space:]]*=[[:space:]]*"[^"]+"' -i -- "${CONSTRAINT_TREE[@]}"
forbid 'sentinel tenant literal in src/main' \
  '"default"([^A-Za-z0-9_]|$)' -- "${MAIN_TREE[@]}"

OVERSIZED_FILES=$(find . -name "*.java" -not -path "*/target/*" -exec wc -l {} + | awk '$1 > 300 && $2 != "total" {print $2 " (" $1 " lines)"}')
if [[ -n "$OVERSIZED_FILES" ]]; then
  echo "ERROR: Found Java files exceeding the 300-line OOP class length gate:"
  echo "$OVERSIZED_FILES"
  exit 1
fi

NO_ASSERT_TESTS=$(python3 -c "
import glob, re
assertion_re = re.compile(r'\b(assert|assertThat|verify|fail)\b')
test_annot_re = re.compile(r'^\s*@(Test|ParameterizedTest)\b')
violations = []
for f in glob.glob('*/src/test/java/**/*.java', recursive=True):
    lines = open(f).readlines()
    in_test = False
    mname = ''
    has_assert = False
    brace_depth = 0
    for i, line in enumerate(lines):
        if test_annot_re.search(line):
            in_test = True
            has_assert = False
            brace_depth = 0
            for j in range(i+1, min(i+10, len(lines))):
                m = re.search(r'\b(?:void|boolean)\s+([a-zA-Z0-9_]+)\s*\(', lines[j])
                if m:
                    mname = m.group(1)
                    break
        if in_test:
            brace_depth += line.count('{') - line.count('}')
            if assertion_re.search(line):
                has_assert = True
            if '{' in line and brace_depth <= 0:
                if not has_assert and mname:
                    violations.append(f'{f}#{mname}')
                in_test = False
                mname = ''
if violations:
    print('\n'.join(violations))
")
if [[ -n "$NO_ASSERT_TESTS" ]]; then
  echo "ERROR: Found test methods without assertions:"
  echo "$NO_ASSERT_TESTS"
  exit 1
fi

echo "✓ Forbidden constructs check passed."

echo "[Step 4/6] Running Log, PII and Secret hygiene checks..."
PII_TREE=(ev-domain ev-planner-core ev-validator ev-service ev-sim)
forbid 'Potential PII logging detected' \
  'log\.(info|debug|warn|error).*([pP]hone|[eE]mail|[cC]oordinates)' -- "${PII_TREE[@]}"
forbid 'Cryptographic private key or hard-coded service token detected' \
  'BEGIN (RSA|EC|DSA|OPENSSH)? ?PRIVATE KEY|AKIA[0-9A-Z]{16}|ghp_[0-9a-zA-Z]{36}' \
  --include='*.yml' --include='*.yaml' --include='*.properties' --include='*.xml' -- .
echo "✓ Log, PII and Secret scanner check passed."

echo "[Step 5/6] Checking integration test environment..."
IT_FILES=$(find . -name "*IT.java" -not -path "*/target/*" 2>/dev/null || true)

if [[ "${CI:-false}" == "true" || "${GITHUB_ACTIONS:-false}" == "true" ]]; then
  if ! command -v docker >/dev/null 2>&1 || ! docker info >/dev/null 2>&1; then
    echo "ERROR: Running in CI but Docker daemon is unreachable! Container-backed integration tests require Docker in CI."
    exit 1
  fi
fi

if command -v docker >/dev/null 2>&1 && docker info >/dev/null 2>&1; then
  echo "✓ Docker is running. Container-backed integration tests enabled."
  echo "✓ Integration test check passed."
else
  if [[ -n "$IT_FILES" ]]; then
    echo "ERROR: Integration tests (*IT.java) exist but Docker is unreachable!"
    echo "Found integration test files:"
    echo "$IT_FILES"
    exit 1
  else
    SKIPPED_STEPS=$((SKIPPED_STEPS + 1))
    echo "[-] Integration tests [SKIPPED: No *IT.java files exist and Docker is unreachable locally; container-backed integration tests will run in CI]"
  fi
fi

echo "[Step 6/6] Release-candidate status check..."
if [[ "${RELEASE_CANDIDATE:-false}" == "true" ]]; then
  echo "Running release-candidate 300-cab benchmark verification..."
  ./mvnw -q test -pl ev-sim -Dtest=BenchmarkScenarioTest
  echo "✓ Release-candidate check passed."
else
  SKIPPED_STEPS=$((SKIPPED_STEPS + 1))
  echo "[-] Release-candidate benchmark [SKIPPED: RELEASE_CANDIDATE not set to true]"
fi

echo "================================================================"
if [[ "$SKIPPED_STEPS" -gt 0 ]]; then
  echo " PASSED WITH ${SKIPPED_STEPS} SKIPPED STEPS: verify.sh completed successfully."
else
  echo " ALL CHECKS PASSED: verify.sh completed successfully."
fi
echo "================================================================"
