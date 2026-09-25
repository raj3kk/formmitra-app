#!/bin/bash
# FormMitra v28 app selftests — pure-Kotlin unit tests (no Android).
# Usage: bash tools/run-selftests.sh
set -u
APP=~/workspace/formmitra-app/app-android
SRC=$APP/app/src/main/java/com/formmitra/app
KOTLINC=~/workspace/phone-agent/tools/kotlinc/bin/kotlinc
STDLIB=~/workspace/phone-agent/tools/kotlinc/lib/kotlin-stdlib.jar
OUT=/tmp/fm-selftest
rm -rf "$OUT"; mkdir -p "$OUT"
TOTAL_FAIL=0

run_test() {
  local name="$1"; shift
  local main="$1"; shift
  echo "== $name =="
  "$KOTLINC" -J-Xmx1g "$@" -d "$OUT/$name" >"$OUT/$name.log" 2>&1
  if [ $? -ne 0 ]; then echo "COMPILE FAILED:"; tail -20 "$OUT/$name.log"; TOTAL_FAIL=$((TOTAL_FAIL+1)); return; fi
  java -cp "$OUT/$name:$STDLIB" "$main" 2>&1 | tee "$OUT/$name.out" | grep -E "^(PASS|FAIL)" | tail -3
  local fails
  fails=$(grep -cE "^(FAIL|Exception in thread)" "$OUT/$name.out" || true)
  echo "-> $name failures: $fails"
  TOTAL_FAIL=$((TOTAL_FAIL+fails))
}

run_test selftest SelfTestKt \
  "$SRC/engine/FormStepLogic.kt" \
  "$APP/tools/selftest/SelfTest.kt"

run_test selftest_agent SelfTestAgentKt \
  "$SRC/engine/FormStepLogic.kt" \
  "$SRC/engine/AgentLoopLogic.kt" \
  "$SRC/engine/AiUsage.kt" \
  "$SRC/agent/LearnLogic.kt" \
  "$APP/tools/selftest/SelfTestAgent.kt"

run_test selftest_cards SelfTestCardsKt \
  "$SRC/engine/FormStepLogic.kt" \
  "$SRC/engine/AgentLoopLogic.kt" \
  "$SRC/agent/CardValidation.kt" \
  "$APP/tools/selftest/SelfTestCards.kt"

run_test selftest_pay SelfTestPayKt \
  "$SRC/engine/PaymentFlow.kt" \
  "$SRC/engine/PrecheckLogic.kt" \
  "$APP/tools/selftest/SelfTestPay.kt"

echo "==============================="
echo "TOTAL FAILURES: $TOTAL_FAIL"
[ "$TOTAL_FAIL" -eq 0 ] && echo "SELFTESTS-OK" || echo "SELFTESTS-FAILED"
