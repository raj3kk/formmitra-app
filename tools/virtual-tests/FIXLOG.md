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
