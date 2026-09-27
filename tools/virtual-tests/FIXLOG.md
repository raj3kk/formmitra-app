# FIXLOG — FormMitra pre-APK virtual test gate
Point-by-point log: symptom → proven root cause → fix → exact retest → final result.
Gate rule: har naya APK banne se PEHLE ye gate green hona chahiye. Red = no APK.

Harness: `tools/virtual-tests/run_virtual_tests.py` — app ka ASLI `captchaDetect()`
JS (FormEngine.kt se extract) real headless Chromium me 9 scenarios par.
Run: `python3 tools/virtual-tests/run_virtual_tests.py` (exit 0 = green).

---

## 2026-09-27 — captchaDetect() false positives (v45)

### Case 1: off-screen captcha markup detected
- **Symptom:** `position:absolute;left:-9999px` par rakha non-zero-size
  captcha div `found=true` de raha tha — screen par kuch nahi, phir bhi
  challenge mana jata.
- **Proven root cause:** `visible()` me size/display/opacity/aria-hidden check
  tha, lekin **viewport intersection check nahi tha**. Off-screen element ka
  `getBoundingClientRect()` non-zero width/height deta hai, isliye pass ho jata tha.
- **Fix (root):** `FormEngine.kt` → `captchaDetect()` → `visible()` me viewport
  intersection joda:
  `if(r.right<=0||r.bottom<=0||r.left>=vw||r.top>=vh) return false;`
- **Retest:** scenario `off-screen` — pehle FAIL (`found=True`), fix ke baad PASS (`found=False`).
- **Result:** ✅ PASS

### Case 2: passive reCAPTCHA v3 badge detected as challenge
- **Symptom:** page ke bottom-right me chhota "protected by reCAPTCHA" badge
  (`.grecaptcha-badge` div) `found=true, kind=recaptcha` de raha tha — ye koi
  solvable challenge nahi hai, user isko solve kar hi nahi sakta.
- **Proven root cause:** class-scan branch me class me "recaptcha" substring
  milte hi element ko challenge maan liya jata tha; `grecaptcha-badge` me
  "recaptcha" substring hai, isliye passive badge bhi pakda jata tha.
- **Fix (root):** class-scan loop me `grecaptcha-badge` wale non-iframe
  elements ko skip kiya. (Asli v2 checkbox hamesha iframe me render hota hai,
  kabhi badge div me nahi — isliye ye skip safe hai.)
- **Retest:** scenario `v3-passive-badge` — pehle FAIL (`found=True`), fix ke baad PASS.
- **Result:** ✅ PASS

### Case 3: v3 badge ke andar wala iframe detected
- **Symptom:** `.grecaptcha-badge` div ke andar Google ka badge iframe
  (src me "recaptcha") `found=true` de raha tha — ye bhi passive hai.
- **Proven root cause:** iframe branch sirf `src` me "recaptcha" dekhta tha;
  badge iframe ka src bhi recaptcha hota hai, aur ancestor check nahi tha.
- **Fix (root):** iframe branch me 5-level ancestor walk — agar koi ancestor
  `grecaptcha-badge` class rakhta hai to iframe skip. Asli v2 checkbox iframe
  kabhi badge div ke andar nahi hota, isliye safe.
- **Retest:** scenario `v3-badge-iframe` (naya scenario fix ke saath joda) — PASS.
- **Result:** ✅ PASS

### Regression check (koi asli challenge miss na ho)
- `visible-v2-checkbox` (real v2 iframe, viewport me): `found=True, kind=recaptcha` ✅
- `visible-image-captcha` (real image captcha): `found=True, kind=image_captcha` ✅
- `hidden-display-none` / `zero-size` / `opacity-zero` / `aria-hidden`: sab `found=False` ✅ (pehle se pass, ab bhi pass)

**Final: 9/9 PASS — gate GREEN (2026-09-27).**

---

## v46 — Learn round-lock atomicity + status root fix (2026-09-27)

### Case 1: v45 ka round lock atomic nahi tha (token write + re-read race)
- **Symptom:** do overlapping ticks/cron workers ek hi logical round do baar chala sakte the — dono token likhte, dono re-read me apna token dekhte (interleaving), dono mehengi web research + AI chalate, aur ek doosre ki progress clobber karta.
- **Proven root cause:** lock "claim" do alag DB writes thi (token+expiry likho → dobara padho → compare karo). Beech me koi atomicity nahi — check-then-act race. Token sirf last-writer-wins tha, serialization nahi.
- **Fix (root):** optimistic concurrency — `LearnProgress.rev` counter; har learn write rev+1 karta hai. Lock claim = EK conditional `UPDATE ... WHERE id AND rev=<old>` (PostgREST `->>` filter); 1 row update = jeeta, 0 rows = haara → skip (fail-closed). Round-end save bhi conditional (rev + apna `lock_until` pin). Conflict par fresh read karke wahi analysis re-apply (dedupe-safe, `applyRoundAnalysis`), max 3 attempts. v45 rows (rev key missing) ke liye `is-null` fallback attempt (bina `or()` ke). Saare DB errors ab check hote hain — silently ignore nahi.
- **Retest:** `test_learn_progress.js` — deterministic concurrent regression: `Promise.all` me 2× `runLearnRound` → exactly 1 research call, rounds +1, rev +2, loser skip; stop-during-round me stop_requested clobber nahi hota; expired lock reclaim; fresh lock skip (zero writes); stall×2 → partial finalize terminal; max-rounds → finalize bina research.
- **Result:** ✅ 41/41 PASS

### Case 2 (CRITICAL): learning kabhi chalti hi nahi — `rowToSession` galat jagah se status padhta tha
- **Symptom:** unit test likhte waqt pakda gaya — `runLearnRound` mock session par "Session nahi mili"/"Status recording — round nahi chahiye" keh raha tha jabki row me status='learning' tha.
- **Proven root cause:** `sessionToRow()` status ko row ke TOP-LEVEL column me likhta hai (`form_data` me `status` key hoti hi nahi), lekin `rowToSession()` status `fd.status` (form_data ke andar) se padhta tha → hamesha default `"recording"` milta tha. `runLearnRound` ka guard `s.status !== "learning"` har asli session ko reject kar deta — **production me AI learning kabhi ek round bhi nahi chalati.** (Ab tak chhupa raha kyunki koi live learning session bani hi nahi thi.)
- **Fix (root):** `rowToSession` ab status top-level `row.status` se padhta hai (DB column = source of truth, `learnTick` bhi usi par filter karta hai); purani rows ke liye `fd.status` fallback rakha.
- **Retest:** naya regression test — top-level status='learning', form_data me status missing → `getTrainerSession().status === 'learning'` aur `runLearnRound` Round 1 chalata hai (pehle "Status recording" kehta tha).
- **Result:** ✅ PASS (41/41 suite me shamil)

### Case 3: round-end save lock khula nahi likhta tha
- **Symptom:** round khatam hone ke baad agla tick "Pichhla round abhi chal raha hai — skip" kehta tha — learning pehle round ke baad atak jati.
- **Proven root cause:** v46 ke round-end save me `p` ab bhi future `round_lock_until` lekar likha jata tha; `clearRoundLock` call missing thi.
- **Fix (root):** conditional save se pehle `clearRoundLock(p)` — DB me lock khula likha jata hai, filter ab bhi purane `myLock` par pin hai (DB state check hota hai, in-memory nahi).
- **Retest:** "lock released after round" + stall test me round 2 ab skip nahi hota.
- **Result:** ✅ PASS

**Final: 41/41 PASS — gate GREEN (2026-09-27).**

### Case 4 (harness, non-product): background verification run transient red — stale mid-edit compile
- **Symptom:** ek background verification run me `test_learn_progress.js` red dikha — concurrent tests me `researchCalls=0`, `rounds=0`, notes me "Session nahi mili.", aur `requestLearnStop` me throw; jabki gate pehle 41/41 green tha.
- **Proven root cause:** product bug nahi — test-harness race. Background run ne `/tmp/fm-test-build` me `trainer.ts` compile kiya us window me jab test file me section 0 (status regression) add ho raha tha — run ke output me section 0 ke 2 tests gayab the (purani file snapshot), aur compile bhi adhoori state par hua tha (pure-function tests pass, session-fetch paths toote). Turant baad fresh `rm -rf` + recompile + rerun par poora suite green.
- **Fix (root):** koi code change nahi — verification ka niyam: red aane par hamesha fresh clean compile + rerun; single run ko verdict nahi manana jab usi window me file edits chal rahe hon.
- **Retest:** `rm -rf /tmp/fm-test-build && npx tsc ... && node test_learn_progress.js` — poora output line-by-line verify.
- **Result:** ✅ 41/41 PASS — gate phir se GREEN (2026-09-27). Production deployment (dpl_PHuPtZ69X2BYMPuwg2CwvEs5mKtP, SHA 194c8063) isse prabhavit nahi — red sirf local harness run me tha.

---

## v45 — Trainer recorder completeness root fix (2026-09-27)

### Case 1: recorder sirf page navigation pakadta tha — taps/typing/selects/scrolls gayab
- **Symptom:** `TrainerRecorderActivity` sirf `onPageFinished` par auto-step banata tha; admin site par tap kare, form bhare, dropdown chune, scroll kare — kuch record nahi hota tha. Recorded training se bane steps me action context khaali.
- **Proven root cause:** WebView me koi DOM instrumentation nahi thi — user interactions ko pakadne ka koi listener/bridge maujood nahi tha.
- **Fix (root):** `TRAINER_JS` inject (har `onPageFinished` par): `click` listener (interactive element → `tap` + `cssPath` selector + tag/text/label/href; checkbox/radio `change` se taaki double-report na ho), `change` listener (`select` → option text, `input/textarea` → `type` + field label + value), `scroll` listener (900ms debounce, |Δ|>200px). `window.FmTrainer.onAction` `@JavascriptInterface` bridge → Kotlin `handleRecordedAction`. Password fields (`type=password`) par `masked:true` — **value kabhi record nahi hoti**. Har step par DOM summary (title/forms/inputs/buttons/links/headings) + auto-generated 1-line Hinglish explanation.
- **Retest:** `bash app-android/tools/run-selftests.sh` — SelfTestV45 pins: js bridge (addJavascriptInterface/@JavascriptInterface/onAction), tap/type/select/scroll capture, password masked, cssPath+element_label+domSum+autoExplain, navigation `"goto"` (not `"khola"`), synchronized(stepLock).
- **Result:** pending (fresh run me — pin 10-15).

### Case 2: step queue thread-unsafe thi (JS bridge background thread se aata hai)
- **Symptom:** bridge calls JavaBridge thread par aate hain; `pendingSteps`/`totalSteps`/`flushing` UI thread se bhi mutate hote the — race me steps kho sakte the ya flush double ho sakta tha. `SimpleDateFormat` thread-unsafe hai.
- **Proven root cause:** `addStep`/`flushSteps` me koi synchronization nahi tha.
- **Fix (root):** `stepLock` monitor — queue mutate, date format, flush claim sab lock ke andar; failed flush par batch wapas queue me (order preserve, data na khoye). `lastAutoUrl` `@Volatile`.
- **Retest:** self-test pin "recorder thread-safe queue" (`synchronized(stepLock)` present).
- **Result:** pending (fresh run me).

### Case 3 (server): `stepsToPattern` note-text ko selector banata tha — exact replay impossible
- **Symptom:** replay pattern ka selector `st.note` (admin ki free-text line) hota tha — exact element targeting nahi, replay unreliable.
- **Proven root cause:** recorded steps me selector field hi nahi tha; function ke paas aur koi option nahi tha.
- **Fix (root):** `TrainerStep` extended (selector/element_tag/element_text/element_label/value/auto_explanation, sab sanitized + capped); `stepsToPattern` ab **asli CSS selector prefer** karta hai (`mode=css`), na ho to purana label fallback; demo `value` kabhi `valueKey` nahi banti (user ki apni values lagti hain); guide prompt me element context aata hai lekin **values EXCLUDED** (master prompt rule).
- **Retest:** `node tools/virtual-tests/test_trainer_steps.js` — real selector→css, selector-less→label, type→fill, no valueKey, note dropped, goto kept, sanitize cap, legacy steps, select.
- **Result:** ✅ 9/9 PASS (2026-09-27).

### Case 4 (test harness): cap test ki galat expectation
- **Symptom:** "pattern capped at 25" FAIL.
- **Proven root cause:** test expectation galat thi — `sanitizePatternSteps` (pre-existing) final pattern ko 6 par cap karta hai; 25 sirf pre-sanitize limit hai.
- **Fix:** test ko real behavior par align kiya (bounded ≤6).
- **Retest:** `node tools/virtual-tests/test_trainer_steps.js`.
- **Result:** ✅ 9/9 PASS.

**Trainer gate status: server 9/9 ✅ — app pins fresh self-test run me.**
