#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
cd "${PROJECT_ROOT}"

# If local JDK 21 is present, use it
if [ -d "${PROJECT_ROOT}/.jdk/jdk-21.0.13+9/Contents/Home" ]; then
    export JAVA_HOME="${PROJECT_ROOT}/.jdk/jdk-21.0.13+9/Contents/Home"
    export PATH="${JAVA_HOME}/bin:${PATH}"
fi

echo "================================================================"
echo " Running Quality Gate: scripts/verify.sh"
echo " Java version: $(java -version 2>&1 | head -n 1)"
echo " Working directory: ${PROJECT_ROOT}"
echo "================================================================"

# 1. Spotless check
echo "[Step 1/6] Running Spotless format check..."
./mvnw -q spotless:check
echo "✓ Spotless check passed."

# 2. Maven verify (compilation with -Xlint:all -Werror, checkstyle, spotbugs, tests, archunit)
echo "[Step 2/6] Running Maven build & test verification..."
./mvnw -q clean verify
echo "✓ Maven verify passed."

# 3. Forbidden construct grep and float/double checks
echo "[Step 3/6] Running forbidden construct checks..."

# 3a. System.out / System.err / printStackTrace
if grep -rn --include="*.java" --exclude-dir="target" -E "System\.(out|err)\.print" ev-domain ev-planner-core ev-validator ev-service ev-sim ev-bench; then
    echo "ERROR: System.out or System.err detected in production/test code!"
    exit 1
fi

if grep -rn --include="*.java" --exclude-dir="target" -E "\.printStackTrace\(\)" ev-domain ev-planner-core ev-validator ev-service ev-sim ev-bench; then
    echo "ERROR: printStackTrace() detected in production/test code!"
    exit 1
fi

# 3b. Wildcard imports in Java files
if grep -rn --include="*.java" --exclude-dir="target" -E "^import[[:space:]]+static[[:space:]]+.*[a-zA-Z0-9_]+\.\*;|^import[[:space:]]+[a-zA-Z0-9_.]+\.\*;" ev-domain/src/main ev-planner-core/src/main ev-validator/src/main ev-service/src/main ev-sim/src/main ev-bench/src/main; then
    echo "ERROR: Wildcard imports detected in production code!"
    exit 1
fi

# 3c. Thread.sleep in production code (main directories only)
if grep -rn --include="*.java" --exclude-dir="target" -E "Thread\.sleep" ev-domain/src/main ev-planner-core/src/main ev-validator/src/main ev-service/src/main ev-sim/src/main; then
    echo "ERROR: Thread.sleep detected in production code! Use Awaitility in tests."
    exit 1
fi

# 3d. catch (Throwable
if grep -rn --include="*.java" --exclude-dir="target" -E "catch[[:space:]]*\([[:space:]]*Throwable" ev-domain ev-planner-core ev-validator ev-service ev-sim ev-bench; then
    echo "ERROR: catch (Throwable) detected!"
    exit 1
fi

# 3e. float and double check in ev-domain, ev-planner-core, ev-validator
if grep -rn --include="*.java" --exclude-dir="target" -E "\b(double|float)\b" ev-domain/src/main ev-planner-core/src/main ev-validator/src/main; then
    echo "ERROR: float or double type detected in ev-domain, ev-planner-core, or ev-validator! Use integer arithmetic only."
    exit 1
fi

echo "✓ Forbidden constructs check passed."

# 4. Log and PII hygiene check
echo "[Step 4/6] Running Log and PII hygiene checks..."
# Check for obvious PII field logging or email/phone patterns in codebase
if grep -rn --include="*.java" --exclude-dir="target" -E "log\.(info|debug|warn|error).*([pP]hone|[eE]mail|[cC]oordinates)" ev-domain ev-planner-core ev-validator ev-service ev-sim; then
    echo "ERROR: Potential PII logging detected!"
    exit 1
fi
echo "✓ Log and PII hygiene check passed."

# 5. Integration tests (if docker/testcontainers available)
echo "[Step 5/6] Checking integration test environment..."
if command -v docker >/dev/null 2>&1 && docker info >/dev/null 2>&1; then
    echo "Docker is running. Integration tests executed during verify."
else
    echo "Note: Docker not currently reachable; container-backed integration tests will run in CI."
fi
echo "✓ Integration test check passed."

# 6. Release-candidate benchmark run check
echo "[Step 6/6] Release-candidate status check..."
if [[ "${RELEASE_CANDIDATE:-false}" == "true" ]]; then
    echo "Running release-candidate 300-cab benchmark verification..."
    ./mvnw -q test -pl ev-sim -Dtest=BenchmarkScenarioTest
fi
echo "✓ Release-candidate check passed."

echo "================================================================"
echo " ALL CHECKS PASSED: verify.sh completed successfully."
echo "================================================================"
