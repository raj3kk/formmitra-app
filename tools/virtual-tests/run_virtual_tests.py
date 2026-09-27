#!/usr/bin/env python3
"""Virtual test gate: runs the REAL app captchaDetect() JS (extracted from
FormEngine.kt) inside real headless Chromium against 8 visibility scenarios.

Exit code 0 = all scenarios pass. Non-zero = gate RED.
"""
import json, os, subprocess, sys, html

BASE = os.path.dirname(os.path.abspath(__file__))
DETECTOR = os.path.join(BASE, "captcha-detect.js")
CHROME = os.path.expanduser("~/.chrome-test" if False else "~/workspace/.chrome-test/extracted/chrome-linux64/chrome")
CHROME = os.path.expanduser("~/workspace/.chrome-test/extracted/chrome-linux64/chrome")
HARNESS = os.path.join(BASE, "harness.html")

SCENARIOS = [
    {"id": "hidden-display-none",
     "markup": '<div class="g-recaptcha" data-sitekey="test" style="display:none;width:304px;height:78px"></div>',
     "expect_found": False,
     "why": "display:none captcha markup must be ignored"},
    {"id": "zero-size",
     "markup": '<div class="g-recaptcha" data-sitekey="test" style="width:0;height:0"></div>',
     "expect_found": False,
     "why": "zero-size sitekey holder must be ignored"},
    {"id": "opacity-zero",
     "markup": '<div class="captcha-box" style="opacity:0;width:200px;height:60px">captcha</div>',
     "expect_found": False,
     "why": "opacity:0 element must be ignored"},
    {"id": "aria-hidden",
     "markup": '<div class="g-recaptcha" data-sitekey="test" aria-hidden="true" style="width:304px;height:78px"></div>',
     "expect_found": False,
     "why": "aria-hidden=true must be ignored"},
    {"id": "off-screen",
     "markup": '<div class="g-recaptcha" data-sitekey="test" style="position:absolute;left:-9999px;top:0;width:304px;height:78px"></div>',
     "expect_found": False,
     "why": "off-viewport element must be ignored (viewport intersection)"},
    {"id": "v3-passive-badge",
     "markup": '<div class="grecaptcha-badge" style="position:fixed;right:4px;bottom:4px;width:70px;height:60px">protected by reCAPTCHA</div>',
     "expect_found": False,
     "why": "passive reCAPTCHA v3 badge is not a solvable challenge"},
    {"id": "v3-badge-iframe",
     "markup": '<div class="grecaptcha-badge" style="position:fixed;right:4px;bottom:4px;width:70px;height:60px"><iframe src="https://www.google.com/recaptcha/api2/bframe?hl=en&v=xyz&k=test" style="width:70px;height:60px;border:0"></iframe></div>',
     "expect_found": False,
     "why": "iframe inside passive v3 badge is not a solvable challenge"},
    {"id": "visible-v2-checkbox",
     "markup": '<iframe src="https://www.google.com/recaptcha/api2/anchor?k=test" style="width:304px;height:78px;border:0"></iframe>',
     "expect_found": True,
     "expect_kind": "recaptcha",
     "why": "real visible v2 checkbox iframe must be detected"},
    {"id": "visible-image-captcha",
     "markup": '<img src="/captcha.jpg" alt="captcha code" style="width:150px;height:50px">',
     "expect_found": True,
     "expect_kind": "image_captcha",
     "why": "real visible image captcha must be detected"},
]

def build_harness():
    with open(DETECTOR, encoding="utf-8") as f:
        detector_src = f.read()
    scenarios_json = json.dumps(SCENARIOS)
    detector_js = json.dumps(detector_src)  # JS string literal
    page = """<!DOCTYPE html><html><head><meta charset="utf-8">
<style>html,body{margin:0;padding:0}</style></head>
<body><div id="stage"></div><pre id="out">RUNNING</pre>
<script>
var SCENARIOS = %s;
var DETECTOR_SRC = %s;
var results = [];
var stage = document.getElementById('stage');
SCENARIOS.forEach(function(sc){
  stage.innerHTML = sc.markup;
  var res;
  try {
    res = JSON.parse((new Function("return (" + DETECTOR_SRC + ")"))());
  } catch(e){ res = {error: String(e)}; }
  var pass = (res.found === sc.expect_found);
  if (pass && sc.expect_found && sc.expect_kind) {
    pass = res.widgets && res.widgets.some(function(w){ return w.kind === sc.expect_kind; });
  }
  results.push({id: sc.id, expect_found: sc.expect_found, got: res, pass: pass, why: sc.why});
});
document.getElementById('out').textContent = "RESULTS_JSON:" + JSON.stringify(results);
document.title = "DONE";
</script></body></html>""" % (scenarios_json, detector_js)
    with open(HARNESS, "w", encoding="utf-8") as f:
        f.write(page)

def run():
    build_harness()
    cmd = [CHROME, "--headless=new", "--no-sandbox", "--disable-gpu",
           "--virtual-time-budget=5000", "--dump-dom", "file://" + HARNESS]
    p = subprocess.run(cmd, capture_output=True, text=True, timeout=90)
    dom = p.stdout
    marker = "RESULTS_JSON:"
    idx = dom.find(marker)
    if idx < 0:
        print("FATAL: results marker not found in dumped DOM");
        print(dom[-2000:])
        return 2
    end = dom.find("</pre>", idx)
    results = json.loads(dom[idx+len(marker):end])
    fails = 0
    print("=" * 70)
    print("VIRTUAL TEST GATE — captchaDetect() in real headless Chromium")
    print("=" * 70)
    for r in results:
        status = "PASS" if r["pass"] else "FAIL"
        if not r["pass"]:
            fails += 1
        got = r["got"]
        detail = "found=%s widgets=%s" % (got.get("found"),
            [w.get("kind") for w in got.get("widgets", [])] if isinstance(got, dict) else got)
        print("[%s] %-22s expect_found=%-5s got: %s" % (status, r["id"], r["expect_found"], detail))
        if not r["pass"]:
            print("       why: %s" % r["why"])
    print("=" * 70)
    print("RESULT: %d/%d PASS" % (len(results)-fails, len(results)))
    return 0 if fails == 0 else 1

if __name__ == "__main__":
    sys.exit(run())
