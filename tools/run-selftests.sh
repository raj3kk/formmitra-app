#!/bin/bash
# FormMitra v28 app selftests — pure-Kotlin unit tests (no Android).
# Usage: bash tools/run-selftests.sh
set -u
# v30: kotlinc/java ke liye JDK chahiye — PATH me java na mile to
# phone-agent wala JDK-17 fallback (warna "java: command not found" se
# saare suite COMPILE FAILED ho jate hain — false red).
if ! command -v java >/dev/null 2>&1; then
  JDK17="$HOME/workspace/phone-agent/tools/jdk-17"
  if [ -x "$JDK17/bin/java" ]; then
    export JAVA_HOME="$JDK17"
    export PATH="$JDK17/bin:$PATH"
  fi
fi
APP=~/workspace/formmitra-app/app-android
SRC=$APP/app/src/main/java/com/formmitra/app
KOTLINC=~/workspace/phone-agent/tools/kotlinc/bin/kotlinc
STDLIB=~/workspace/phone-agent/tools/kotlinc/lib/kotlin-stdlib.jar
ANDR_JAR=~/workspace/build-tools/android-sdk/platforms/android-34/android.jar
OUT=/tmp/fm-selftest
rm -rf "$OUT"; mkdir -p "$OUT"
TOTAL_FAIL=0
TOTAL_PASS=0

run_test() {
  local name="$1"; shift
  local main="$1"; shift
  echo "== $name =="
  "$KOTLINC" -J-Xmx1g "$@" -d "$OUT/$name" >"$OUT/$name.log" 2>&1
  if [ $? -ne 0 ]; then echo "COMPILE FAILED:"; tail -20 "$OUT/$name.log"; TOTAL_FAIL=$((TOTAL_FAIL+1)); return; fi
  java -cp "$OUT/$name:$STDLIB" "$main" 2>&1 | tee "$OUT/$name.out" | grep -E "^(PASS|FAIL)" | tail -3
  local fails passes
  fails=$(grep -cE "^(FAIL|Exception in thread)" "$OUT/$name.out" || true)
  passes=$(grep -cE "^PASS" "$OUT/$name.out" || true)
  echo "-> $name PASS: $passes FAIL: $fails"
  TOTAL_FAIL=$((TOTAL_FAIL+fails))
  TOTAL_PASS=$((TOTAL_PASS+passes))
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

# v29: P2/P3/P4 pure logic — android.jar classpath par (org.json + android
# stubs compile ke liye; runtime par sirf pure functions chalte hain).
run_test_v29() {
  local name="selftest_v29"
  echo "== $name =="
  "$KOTLINC" -J-Xmx1g -cp "$ANDR_JAR" \
    "$SRC/agent/TagRegistry.kt" \
    "$SRC/agent/CardJson.kt" \
    "$SRC/agent/CardSaveVerifier.kt" \
    "$SRC/agent/VoiceOutput.kt" \
    "$APP/tools/selftest/SelfTestV29.kt" \
    -d "$OUT/$name" >"$OUT/$name.log" 2>&1
  if [ $? -ne 0 ]; then echo "COMPILE FAILED:"; tail -20 "$OUT/$name.log"; TOTAL_FAIL=$((TOTAL_FAIL+1)); return; fi
  # v30: detailsOf/storedValuesFrom tests org.json ko runtime par chhoote
  # hain. android.jar ke org.json classes "Stub!" hain (runtime par
  # crash) — isliye REAL org.json reference jar pehle, android.jar baad me.
  ORGJSON=$APP/tools/lib/json-20231013.jar
  java -cp "$OUT/$name:$ORGJSON:$STDLIB:$ANDR_JAR" SelfTestV29Kt 2>&1 | tee "$OUT/$name.out" | grep -E "^(PASS|FAIL)" | tail -3
  local fails passes
  fails=$(grep -cE "^(FAIL|Exception in thread)" "$OUT/$name.out" || true)
  passes=$(grep -cE "^PASS" "$OUT/$name.out" || true)
  echo "-> $name PASS: $passes FAIL: $fails"
  TOTAL_FAIL=$((TOTAL_FAIL+fails))
  TOTAL_PASS=$((TOTAL_PASS+passes))
}
run_test_v29

# v29 P8: realtime pure logic — WsFrame/PhoenixMsg/MiniJson/RealtimeChannel/
# RealtimeCrypto (RealtimeSocket.kt ke pure objects; runtime par sirf ye
# chhute hain — android/org.json stubs load nahi hote).

run_test_v29_realtime() {
  local name="selftest_v29_realtime"
  echo "== $name =="
  "$KOTLINC" -J-Xmx1g -cp "$ANDR_JAR" \
    "$SRC/agent/RealtimeSocket.kt" \
    "$APP/tools/selftest/SelfTestV29Realtime.kt" \
    -d "$OUT/$name" >"$OUT/$name.log" 2>&1
  if [ $? -ne 0 ]; then echo "COMPILE FAILED:"; tail -20 "$OUT/$name.log"; TOTAL_FAIL=$((TOTAL_FAIL+1)); return; fi
  java -cp "$OUT/$name:$STDLIB" SelfTestV29RealtimeKt 2>&1 | tee "$OUT/$name.out" | grep -E "^(PASS|FAIL)" | tail -3
  local fails passes
  fails=$(grep -cE "^(FAIL|Exception in thread)" "$OUT/$name.out" || true)
  passes=$(grep -cE "^PASS" "$OUT/$name.out" || true)
  echo "-> $name PASS: $passes FAIL: $fails"
  TOTAL_FAIL=$((TOTAL_FAIL+fails))
  TOTAL_PASS=$((TOTAL_PASS+passes))
}
run_test_v29_realtime

# v36: SMART COORDINATION pure logic — PreflightPlan / EscalationLadder /
# AiUsage model-ledger (NO cap — user order 2026-09-26: token par koi
# restriction nahi; hisaab admin-only) / LearnLogic work-patterns+
# stepEscalation / PageStructureHash / GateAudit trail / IdempotencyGuard /
# ErrorCatcher full-detail (org.json chahiye — compile android.jar par,
# runtime par REAL org.json pehle) / LiveActivity indicator labels.
run_test_v36() {
  local name="selftest_v36"
  echo "== $name =="
  "$KOTLINC" -J-Xmx1g -cp "$ANDR_JAR" \
    "$SRC/engine/PreflightPlan.kt" \
    "$SRC/engine/EscalationLadder.kt" \
    "$SRC/engine/AiUsage.kt" \
    "$SRC/agent/LearnLogic.kt" \
    "$SRC/agent/PageStructureHash.kt" \
    "$SRC/agent/WorkPatternStore.kt" \
    "$SRC/agent/GateAudit.kt" \
    "$SRC/agent/LiveActivity.kt" \
    "$SRC/engine/IdempotencyGuard.kt" \
    "$SRC/engine/ErrorCatcher.kt" \
    "$SRC/engine/FormApi.kt" \
    "$SRC/engine/AgentLoopLogic.kt" \
    "$SRC/agent/AgentApi.kt" \
    "$SRC/BuildConfig.java" \
    "$APP/tools/selftest/SelfTestV36.kt" \
    -d "$OUT/$name" >"$OUT/$name.log" 2>&1
  if [ $? -ne 0 ]; then echo "COMPILE FAILED:"; tail -20 "$OUT/$name.log"; TOTAL_FAIL=$((TOTAL_FAIL+1)); return; fi
  ORGJSON=$APP/tools/lib/json-20231013.jar
  java -Dfm.app.dir="$APP" -cp "$OUT/$name:$ORGJSON:$STDLIB:$ANDR_JAR" SelfTestV36Kt 2>&1 | tee "$OUT/$name.out" | grep -E "^(PASS|FAIL)" | tail -3
  local fails passes
  fails=$(grep -cE "^(FAIL|Exception in thread)" "$OUT/$name.out" || true)
  passes=$(grep -cE "^PASS" "$OUT/$name.out" || true)
  echo "-> $name PASS: $passes FAIL: $fails"
  TOTAL_FAIL=$((TOTAL_FAIL+fails))
  TOTAL_PASS=$((TOTAL_PASS+passes))
}
run_test_v36

# Point 14: DetailBatchLogic (pure Kotlin, no Android)
run_test selftest_details SelfTestDetailsKt \
  "$SRC/engine/DetailBatchLogic.kt" \
  "$APP/tools/selftest/SelfTestDetails.kt"

# Point 15 + 16: GateLogic + DocCompressPolicy (pure Kotlin, no Android)
run_test selftest_gates SelfTestGatesKt \
  "$SRC/engine/GateLogic.kt" \
  "$SRC/engine/DocCompressPolicy.kt" \
  "$APP/tools/selftest/SelfTestGates.kt"

# Point 24 (revised): CardUnlockPolicy (pure Kotlin, no Android)
run_test selftest_unlock SelfTestUnlockKt \
  "$SRC/engine/CardUnlockPolicy.kt" \
  "$APP/tools/selftest/SelfTestUnlock.kt"

# Point 26 (+clarification): SmsOtpPolicy — no-nagging (pure Kotlin)
run_test selftest_smsotp SelfTestSmsOtpKt \
  "$SRC/engine/SmsOtpPolicy.kt" \
  "$APP/tools/selftest/SelfTestSmsOtp.kt"

# CONTRACT SYNC (2026-09-26): server authoritative vocabulary — whitelist +
# spec mapping + kinds + plan fields + PIN actions + operator commands +
# events + endpoints. Pinned counts (koi miss = FAIL).
run_test selftest_contract SelfTestContractKt \
  "$SRC/engine/AgentLoopLogic.kt" \
  "$SRC/engine/FormStepLogic.kt" \
  "$SRC/engine/GateLogic.kt" \
  "$APP/tools/selftest/SelfTestContract.kt"

# POINT 29 (v31): SettingsStore pure logic — server sync mapping
# (serverPatchBody / mergePendingToBody / localPairsFromServer),
# formatBytes, deleteFilesUnder (real FS).
# NOTE: real DocsStore ka dep-tree (CryptoVault → AgentApi → AgentLoopLogic…)
# selftest me nahi uthate — tools/selftest/DocsStoreLinkStub.kt sirf linker
# ke liye hai (APK build me kabhi nahi jata; build-apk.sh sirf app/src/main
# compile karta hai). Tested functions pure hain, Context touch nahi hota.
run_test_settings() {
  local name="selftest_settings"
  echo "== $name =="
  "$KOTLINC" -J-Xmx1g -cp "$ANDR_JAR" \
    "$SRC/agent/SettingsStore.kt" \
    "$APP/tools/selftest/DocsStoreLinkStub.kt" \
    "$APP/tools/selftest/SelfTestSettings.kt" \
    -d "$OUT/$name" >"$OUT/$name.log" 2>&1
  if [ $? -ne 0 ]; then echo "COMPILE FAILED:"; tail -20 "$OUT/$name.log"; TOTAL_FAIL=$((TOTAL_FAIL+1)); return; fi
  # android.jar ke org.json stubs runtime par "Stub!" throw karte hain —
  # REAL org.json pehle, android.jar classpath par NAHI (sirf pure
  # functions chalte hain, Context kabhi touch nahi hota).
  ORGJSON=$APP/tools/lib/json-20231013.jar
  java -cp "$OUT/$name:$ORGJSON:$STDLIB" SelfTestSettingsKt 2>&1 | tee "$OUT/$name.out" | grep -E "^(PASS|FAIL)" | tail -3
  local fails passes
  fails=$(grep -cE "^(FAIL|Exception in thread)" "$OUT/$name.out" || true)
  passes=$(grep -cE "^PASS" "$OUT/$name.out" || true)
  echo "-> $name PASS: $passes FAIL: $fails"
  TOTAL_FAIL=$((TOTAL_FAIL+fails))
  TOTAL_PASS=$((TOTAL_PASS+passes))
}
run_test_settings

# v34 (Phase 2A): OtpParser + OtpFieldDetect (pure Kotlin, no Android)
run_test selftest_otp SelfTestOtpKt \
  "$SRC/engine/OtpParser.kt" \
  "$SRC/engine/OtpFieldDetect.kt" \
  "$APP/tools/selftest/SelfTestOtp.kt"

# v35: ErrorCatcher pure parts — masking/friendly/report/start-failure
# contract (android.jar compile classpath par; runtime par sirf pure
# functions chalte hain — Android stubs kabhi call nahi hote).
run_test_catcher() {
  local name="selftest_catcher"
  echo "== $name =="
  "$KOTLINC" -J-Xmx1g -cp "$ANDR_JAR" \
    "$SRC/engine/ErrorCatcher.kt" \
    "$APP/tools/selftest/SelfTestCatcher.kt" \
    -d "$OUT/$name" >"$OUT/$name.log" 2>&1
  if [ $? -ne 0 ]; then echo "COMPILE FAILED:"; tail -20 "$OUT/$name.log"; TOTAL_FAIL=$((TOTAL_FAIL+1)); return; fi
  java -cp "$OUT/$name:$STDLIB:$ANDR_JAR" SelfTestCatcherKt 2>&1 | tee "$OUT/$name.out" | grep -E "^(PASS|FAIL)" | tail -3
  local fails passes
  fails=$(grep -cE "^(FAIL|Exception in thread)" "$OUT/$name.out" || true)
  passes=$(grep -cE "^PASS" "$OUT/$name.out" || true)
  echo "-> $name PASS: $passes FAIL: $fails"
  TOTAL_FAIL=$((TOTAL_FAIL+fails))
  TOTAL_PASS=$((TOTAL_PASS+passes))
}
run_test_catcher

# v37: crash-fix pins — WorkManager/ArchTaskExecutor NoClassDefFoundError.
# artifacts.txt closure (har entry: file maujood + valid zip + version label
# sahi), core-runtime me ArchTaskExecutor.class, transitive version floors,
# aur (pichla build dex ho to) dex DEFINITION check. Dex gate ka authoritative
# roop build-apk.sh step 4b me hai (build fail karta hai).
run_test_v37() {
  local name="selftest_v37"
  echo "== $name =="
  "$KOTLINC" -J-Xmx1g -cp "$ANDR_JAR" \
    "$APP/tools/selftest/SelfTestV37.kt" \
    "$SRC/agent/GlobalPlaybook.kt" \
    "$SRC/agent/WorkPatternStore.kt" \
    "$SRC/agent/RepeatRun.kt" \
    "$SRC/engine/DestructivePolicy.kt" \
    "$SRC/engine/RunMemory.kt" \
    "$SRC/engine/StateInference.kt" \
    "$SRC/engine/MemoryWiring.kt" \
    "$SRC/engine/ErrorCatcher.kt" \
    "$SRC/agent/CardJson.kt" \
    "$SRC/agent/AgentApi.kt" \
    "$SRC/engine/AgentLoopLogic.kt" \
    "$SRC/engine/FormApi.kt" \
    "$SRC/BuildConfig.java" \
    -d "$OUT/$name" >"$OUT/$name.log" 2>&1
  if [ $? -ne 0 ]; then echo "COMPILE FAILED:"; tail -20 "$OUT/$name.log"; TOTAL_FAIL=$((TOTAL_FAIL+1)); return; fi
  # v37 PART 2: real org.json (android.jar ke stubs runtime par "Stub!"
  # throw karte hain); -Dfm.app.dir se source-pin checks ko app tree milta hai.
  ORGJSON=$APP/tools/lib/json-20231013.jar
  java -Dfm.app.dir="$APP" -cp "$OUT/$name:$ORGJSON:$STDLIB" SelfTestV37Kt 2>&1 | tee "$OUT/$name.out" | grep -E "^(PASS|FAIL|SKIP)" | tail -5
  local fails passes
  fails=$(grep -cE "^(FAIL|Exception in thread)" "$OUT/$name.out" || true)
  passes=$(grep -cE "^PASS" "$OUT/$name.out" || true)
  echo "-> $name PASS: $passes FAIL: $fails"
  TOTAL_FAIL=$((TOTAL_FAIL+fails))
  TOTAL_PASS=$((TOTAL_PASS+passes))
}
run_test_v37

# v38: Live WebView pins — pure-JVM (WebView instantiate nahi hota).
# LiveWebViewHost (power policy, host-client contract) + LiveActivity
# (recover label, terminal mapping) + ErrorCatcher (host ka report path).
run_test_v38() {
  local name="selftest_v38"
  echo "== $name =="
  "$KOTLINC" -J-Xmx1g -cp "$ANDR_JAR" \
    "$APP/tools/selftest/SelfTestV38.kt" \
    "$SRC/engine/LiveWebViewHost.kt" \
    "$SRC/agent/LiveActivity.kt" \
    "$SRC/engine/ErrorCatcher.kt" \
    -d "$OUT/$name" >"$OUT/$name.log" 2>&1
  if [ $? -ne 0 ]; then echo "COMPILE FAILED:"; tail -20 "$OUT/$name.log"; TOTAL_FAIL=$((TOTAL_FAIL+1)); return; fi
  # Runtime classpath me ANDR_JAR bhi: HostWebViewClient class load hote waqt
  # uske superclass android.webkit.WebViewClient ki zaroorat padti hai
  # (instantiate nahi hota — sirf class-load, stub-safe).
  java -cp "$OUT/$name:$STDLIB:$ANDR_JAR" SelfTestV38Kt 2>&1 | tee "$OUT/$name.out" | grep -E "^(PASS|FAIL|SKIP)" | tail -5
  local fails passes
  fails=$(grep -cE "^(FAIL|Exception in thread)" "$OUT/$name.out" || true)
  passes=$(grep -cE "^PASS" "$OUT/$name.out" || true)
  echo "-> $name PASS: $passes FAIL: $fails"
  TOTAL_FAIL=$((TOTAL_FAIL+fails))
  TOTAL_PASS=$((TOTAL_PASS+passes))
}
run_test_v38

echo "==============================="
echo "TOTAL PASS: $TOTAL_PASS"
echo "TOTAL FAILURES: $TOTAL_FAIL"
[ "$TOTAL_FAIL" -eq 0 ] && echo "SELFTESTS-OK" || echo "SELFTESTS-FAILED"
