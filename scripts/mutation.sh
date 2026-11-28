#!/usr/bin/env bash
# Mutation testing (PIT) of the code that guards money and access. It changes the code in small ways (flip a comparison,
# drop a call, return null) and checks that at least one test fails each time. A surviving change means a test is missing.
# Each module has a minimum score; the build fails below it. Needs Maven and no Docker. Run it with `make mutation`.
# Not part of `mvn verify` because it is slow (a few minutes). Report: <module>/target/pit-reports/index.html
set -euo pipefail
cd "$(dirname "$0")/.."

run() { # module, classes, tests, minimum score (percent)
  local module=$1 classes=$2 tests=$3 min=$4
  echo "== $module: $classes (minimum $min%)"
  local out; out=$(mktemp)
  if ! mvn -q -Pmutation -Djacoco.skip=true -pl "$module" test-compile org.pitest:pitest-maven:mutationCoverage \
    -DtargetClasses="$classes" -DtargetTests="$tests" -DmutationThreshold="$min" >"$out" 2>&1; then
    grep -E ">> Generated [0-9]+ mutations|Test strength|Mutation score|ERROR" "$out" | head -8
    rm -f "$out"
    echo "MUTATION FAILED in $module (below $min% or a build error)"
    exit 1
  fi
  grep -E ">> Generated [0-9]+ mutations|Test strength" "$out"
  rm -f "$out"
}

mvn -q -DskipTests -Djacoco.skip=true install >/dev/null

run trading-core "com.adrianrevilla.trading.core.domain.*" "com.adrianrevilla.trading.core.domain.*" ${MUTATION_MIN_CORE:-90}
run trading-application "com.adrianrevilla.trading.application.service.ModeService,com.adrianrevilla.trading.application.service.ProdSwitchPolicy,com.adrianrevilla.trading.application.service.AgentService,com.adrianrevilla.trading.application.service.AgentsDayService" "com.adrianrevilla.trading.application.service.*" ${MUTATION_MIN_APPLICATION:-95}
run trading-risk "com.adrianrevilla.trading.risk.*" "com.adrianrevilla.trading.risk.*" ${MUTATION_MIN_RISK:-95}
run trading-execution "com.adrianrevilla.trading.execution.*" "com.adrianrevilla.trading.execution.*" ${MUTATION_MIN_EXECUTION:-95}
echo "MUTATION OK"
