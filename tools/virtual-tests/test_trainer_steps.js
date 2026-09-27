#!/usr/bin/env node
/** Unit tests: v45 TrainerStep extension + stepsToPattern root fix.
 *  Compile first: cd ~/workspace/repos/formmitra && npx tsc lib/agent/trainer.ts --outDir /tmp/fm-test-build --module commonjs --target es2020 --esModuleInterop --skipLibCheck
 *  Exit 0 = all pass.
 *  Recorder ab asli CSS selector bhejta hai (st.selector) — stepsToPattern ko
 *  use prefer karna chahiye (mode=css). Purane selector-less steps par purana
 *  label fallback rahe. Demo values kabhi pattern me nahi (valueKey nahi).
 */
const path = "/tmp/fm-test-build/trainer.js";
let T;
try {
  T = require(path);
} catch (e) {
  console.log("FATAL: require failed:", e.message);
  process.exit(2);
}
const { stepsToPattern } = T;

let pass = 0, fail = 0;
function ok(name, cond, extra) {
  if (cond) { pass++; console.log("[PASS] " + name); }
  else { fail++; console.log("[FAIL] " + name + (extra ? " — " + extra : "")); }
}

const base = { url: "https://example.com/form", title: "Form", action: "tap", note: "", dom_summary: "" };

// 1. Real selector → mode=css
const p1 = stepsToPattern([
  Object.assign({}, base, { selector: "button#loginBtn", element_label: "Login", note: "" }),
]);
ok("real selector preferred (mode=css)",
  p1.length === 1 && p1[0].selector === "button#loginBtn" && p1[0].mode === "css",
  JSON.stringify(p1));

// 2. Selector nahi → purana label fallback
const p2 = stepsToPattern([
  Object.assign({}, base, { selector: "", note: "Login button dabao" }),
]);
ok("selector-less falls back to label",
  p2.length === 1 && p2[0].selector === "Login button dabao" && p2[0].mode === "label",
  JSON.stringify(p2));

// 3. type → fill mapping + css
const p3 = stepsToPattern([
  Object.assign({}, base, { action: "type", selector: "input#phone", element_label: "Mobile number", value: "9876543210" }),
]);
ok("type maps to fill with css",
  p3.length === 1 && p3[0].action === "fill" && p3[0].mode === "css",
  JSON.stringify(p3));

// 4. Demo value kabhi valueKey nahi banti
ok("no valueKey from demo value", p3.length === 1 && p3[0].valueKey === undefined);

// 5. Non-replayable action drop (note)
const p5 = stepsToPattern([Object.assign({}, base, { action: "note", note: "kuch" })]);
ok("note action dropped from pattern", p5.length === 0);

// 6. goto replayable
const p6 = stepsToPattern([
  Object.assign({}, base, { action: "goto", url: "https://example.com/next", selector: "" }),
]);
ok("goto kept in pattern", p6.length === 1 && p6[0].action === "goto");

// 7. sanitize cap (pre-existing: final pattern max 6 steps)
const many = [];
for (let i = 0; i < 40; i++) many.push(Object.assign({}, base, { action: "tap", selector: "#b" + i }));
const pc = stepsToPattern(many);
ok("pattern bounded (sanitize cap)", pc.length > 0 && pc.length <= 6,
  "len=" + pc.length);

// 8. Purane steps (bina naye fields) crash nahi karte
const legacy = { url: "https://x.com", title: "T", action: "tap", note: "tap karo", dom_summary: "" };
let p8 = null, threw = false;
try { p8 = stepsToPattern([legacy]); } catch (e) { threw = true; }
ok("legacy steps handled", !threw && p8 && p8.length === 1 && p8[0].mode === "label");

// 9. select replayable
const p9 = stepsToPattern([
  Object.assign({}, base, { action: "select", selector: "select#state", element_label: "State", value: "Bihar" }),
]);
ok("select kept with css", p9.length === 1 && p9[0].action === "select" && p9[0].mode === "css");

console.log("\n" + pass + " PASS / " + fail + " FAIL");
if (fail > 0) process.exit(1);
