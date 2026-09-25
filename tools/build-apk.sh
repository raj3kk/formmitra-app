#!/bin/bash
# FormMitra APK build v3 — aapt2 + kotlinc + d8 + apksigner (no Gradle).
# Pattern: ~/workspace/browse-agent/app-android/tools/build-apk.sh (READ-ONLY reuse).
# Clean client app: WebView shell + WorkManager digest. NO automation, NO accessibility.
# ~~~~~ SOURCE OF TRUTH ~~~~~
# VERSION_CODE / VERSION_NAME / SITE_URL sirf yahin badlo — script BuildConfig.java sync karta hai.
# INVARIANT: version_code hamesha APK manifest ke real versionCode ke barabar (haath se max+1 kabhi nahi).
set -e
PTOOLS=~/workspace/phone-agent/tools   # READ-ONLY reuse: sdk, jdk, kotlinc, build-tools, deps
FA=~/workspace/formmitra-app/app-android
cd $PTOOLS
APP=$FA/app/src/main
SDK=$PTOOLS/android-sdk
BT=$SDK/build-tools/34.0.0
OUT=$FA/tools/apk-build
rm -rf $OUT && mkdir -p $OUT/{aar,classes,dex,res}

export JAVA_HOME=$PTOOLS/jdk-17
export PATH=$JAVA_HOME/bin:$PATH
APPID="com.formmitra.app"
VERSION_CODE=22
VERSION_NAME="1.0.22-v22"
SITE_URL="https://formmitra-git-main-webbuilder1.vercel.app/"
# Output APK name parameterized — v1 APK (formmitra-v1.apk) untouched rehta hai.
APK_NAME="formmitra-v${VERSION_CODE}.apk"

# BuildConfig.java sync (manual build me Gradle nahi hai)
sed -i -e "s/VERSION_NAME = \"[^\"]*\"/VERSION_NAME = \"$VERSION_NAME\"/" \
       -e "s/VERSION_CODE = [0-9]*/VERSION_CODE = $VERSION_CODE/" \
       -e "s|SITE_URL = \"[^\"]*\"|SITE_URL = \"$SITE_URL\"|" \
  $APP/java/com/formmitra/app/BuildConfig.java
grep -E "VERSION_CODE|VERSION_NAME|SITE_URL" $APP/java/com/formmitra/app/BuildConfig.java

echo "== 1. AARs extract =="
CP="$SDK/platforms/android-34/android.jar"
JARS=()
while IFS='=' read -r coord dest; do
  case "$dest" in
    *.aar)
      n=$(basename "$dest" .aar)
      mkdir -p $OUT/aar/$n && unzip -q -o "$dest" -d $OUT/aar/$n
      if [ -f $OUT/aar/$n/classes.jar ]; then JARS+=("$OUT/aar/$n/classes.jar"); fi
      ;;
    *.jar) JARS+=("$dest") ;;
  esac
done < $PTOOLS/deps/artifacts.txt
# zxing-core (UPI QR): compile classpath me bhi chahiye (d8 me alag se add hai)
if [ -f "$FA/tools/zxing-core-3.5.3.jar" ]; then JARS+=("$FA/tools/zxing-core-3.5.3.jar"); fi
for j in "${JARS[@]}"; do CP="$CP:$j"; done
echo "jars: ${#JARS[@]}"

echo "== 2. aapt2 compile+link =="
sed -e "s/\${applicationId}/$APPID/g" -e "s|<manifest |<manifest package=\"$APPID\" |" $FA/app/AndroidManifest.xml > $OUT/AndroidManifest.xml
$BT/aapt2 compile --dir $APP/res -o $OUT/res.zip
AAR_RES_ARGS=()
for d in $OUT/aar/*/; do
  if [ -d "$d/res" ]; then
    n=$(basename "$d")
    if $BT/aapt2 compile --dir "$d/res" -o "$OUT/aar-res-$n.zip" 2>/dev/null; then
      AAR_RES_ARGS+=("-R" "$OUT/aar-res-$n.zip")
    fi
  fi
done
echo "aar res zips: $((${#AAR_RES_ARGS[@]} / 2))"

# == J1: FCM config (google-services.json → fcm_values.xml) ==
# Firebase project clip-flow-685a5 reuse. User Firebase console me
# com.formmitra.app add karke updated google-services.json dega.
# - json me FormMitra client mile to res values generate (build-time only,
#   $OUT me — repo me COMMIT NAHI hota, koi secret file nahi banti).
# - na mile to skip: FirebaseApp.initializeApp null dega → FcmPush graceful
#   degrade, polling fallback (FormTaskWorker 30-min + NetWake + WakeWorker).
# Firebase options code me hardcode NAHI — sab json se aata hai.
FCM_RES_ARGS=()
GSJSON=""
for cand in "$FA/app/google-services.json" "$HOME/workspace/user/files/google-services.json"; do
  if [ -f "$cand" ]; then GSJSON="$cand"; break; fi
done
if [ -n "$GSJSON" ]; then
  echo "google-services.json: $GSJSON"
  mkdir -p $OUT/fcm-res/values
  if python3 - "$GSJSON" "$OUT/fcm-res/values/fcm_values.xml" <<'PYEOF'
import json, sys, xml.sax.saxutils as sx
src, dst = sys.argv[1], sys.argv[2]
try:
    d = json.load(open(src))
except Exception as e:
    print('FCM: json parse fail: %s' % e); sys.exit(1)
proj = d.get('project_info', {}) or {}
found = None
for c in d.get('client', []) or []:
    ci = c.get('client_info') or {}
    aci = ci.get('android_client_info') or {}
    if aci.get('package_name') == 'com.formmitra.app':
        found = c; break
if not found:
    print('FCM: com.formmitra.app client json me nahi — polling fallback')
    sys.exit(1)
api_key = ''
for k in found.get('api_key') or []:
    if k.get('current_key'):
        api_key = k['current_key']; break
vals = {
    'google_app_id': (found.get('client_info') or {}).get('mobilesdk_app_id', ''),
    'gcm_defaultSenderId': str(proj.get('project_number', '')),
    'google_api_key': api_key,
    'google_project_id': proj.get('project_id', ''),
    'google_storage_bucket': proj.get('storage_bucket', ''),
}
for req in ('google_app_id', 'gcm_defaultSenderId', 'google_api_key', 'google_project_id'):
    if not vals[req]:
        print('FCM: missing ' + req); sys.exit(1)
xml = '<?xml version="1.0" encoding="utf-8"?>\n<resources>\n'
for k, v in vals.items():
    if v:
        xml += '    <string name="%s" translatable="false">%s</string>\n' % (k, sx.escape(v))
xml += '</resources>\n'
open(dst, 'w').write(xml)
print('FCM: fcm_values.xml OK (project=%s)' % vals['google_project_id'])
PYEOF
  then
    $BT/aapt2 compile --dir $OUT/fcm-res -o $OUT/fcm-res.zip
    FCM_RES_ARGS=(-R $OUT/fcm-res.zip)
  fi
else
  echo "FCM: google-services.json nahi mila — polling fallback (build nahi tootega)"
fi

$BT/aapt2 link -o $OUT/base.apk \
  -I $SDK/platforms/android-34/android.jar \
  --manifest $OUT/AndroidManifest.xml \
  --min-sdk-version 26 --target-sdk-version 34 \
  --version-code $VERSION_CODE --version-name "$VERSION_NAME" \
  --rename-manifest-package "$APPID" \
  --auto-add-overlay \
  --java $OUT/gen \
  "${AAR_RES_ARGS[@]}" \
  "${FCM_RES_ARGS[@]}" \
  $OUT/res.zip
# R.java bani ya nahi — nahi bani to aage badhne ka matlab nahi
test -f $OUT/gen/com/formmitra/app/R.java

echo "== 3. kotlinc =="
find $APP/java -name "*.kt" -o -name "*.java" > $OUT/sources.txt
wc -l $OUT/sources.txt
$PTOOLS/kotlinc/bin/kotlinc -J-Xmx2g -jvm-target 17 -no-reflect \
  -cp "$CP" -d $OUT/classes @$OUT/sources.txt 2>&1 | grep -v "^warning:"; test ${PIPESTATUS[0]} -eq 0

echo "== 3b. library R classes =="
# Manual build me library R classes generate nahi hoti — aapt2 sirf app package
# ki R.java banata hai. Merged resource table single hai, to har AAR package ke
# liye app R.java ki copy hi sahi R class hai.
R_JAVA=$OUT/gen/com/formmitra/app/R.java
mkdir -p $OUT/gen2
for aar in $(find $PTOOLS/deps -name "*.aar"); do
  pkg=$(unzip -p "$aar" AndroidManifest.xml 2>/dev/null | grep -o 'package="[^"]*"' | head -1 | cut -d'"' -f2)
  if [ -n "$pkg" ] && [ "$pkg" != "$APPID" ]; then
    dstdir=$OUT/gen2/$(echo "$pkg" | tr . /)
    if [ ! -f "$dstdir/R.java" ]; then
      mkdir -p "$dstdir"
      sed "s/^package com\.formmitra\.app;/package $pkg;/" "$R_JAVA" > "$dstdir/R.java"
    fi
  fi
done
echo "library R packages: $(find $OUT/gen2 -name 'R.java' | wc -l)"
javac -d $OUT/classes $(find $OUT/gen2 -name "R.java") 2>&1 | head -5; test ${PIPESTATUS[0]} -eq 0

echo "== 4. d8 =="
# AGENTS.md lesson (d8 me kotlin-stdlib VERSION MATCH): deps/ ke purane kotlin-stdlib
# jars (1.7.x — EnumEntriesKt missing, kotlinc 1.9 ka generated code crash karega)
# d8 input se BAHAR; compiler ka bundled kotlin-stdlib.jar (1.9+) dex karo.
# (nahi to duplicate-class error ya runtime NoClassDefFoundError).
D8_JARS=()
for j in "${JARS[@]}"; do
  case "$j" in
    *kotlin-stdlib*) continue ;;
    *) D8_JARS+=("$j") ;;
  esac
done
D8_JARS+=("$PTOOLS/kotlinc/lib/kotlin-stdlib.jar")
echo "d8 jars: ${#D8_JARS[@]} (kotlin-stdlib = compiler bundled)"
$BT/d8 --min-api 26 --lib $SDK/platforms/android-34/android.jar \
  --output $OUT/dex $(find $OUT/classes -name "*.class") "${D8_JARS[@]}" 2>&1 | tail -5

echo "== 5. package + sign =="
cd $OUT
for d in dex/classes*.dex; do cp "$d" ./$(basename $d); done
zip -q -j base.apk classes.dex classes2.dex 2>/dev/null || zip -q -j base.apk classes.dex
$BT/zipalign -f 4 base.apk aligned.apk
# Release key (pehli baar banta hai, phir hamesha reuse — updates clean install honge).
# BrowseAgent/ClipFlow keys se ALAG — FormMitra bilkul separate project hai.
if [ ! -f $FA/tools/formmitra-release.keystore ]; then
  keytool -genkeypair -keystore $FA/tools/formmitra-release.keystore -storepass formmitra123 \
    -keypass formmitra123 -alias formmitra -keyalg RSA -keysize 2048 -validity 9125 \
    -dname "CN=FormMitra,O=FormMitra,C=IN" 2>/dev/null
fi
$BT/apksigner sign --ks $FA/tools/formmitra-release.keystore --ks-pass pass:formmitra123 \
  --key-pass pass:formmitra123 --out $FA/tools/$APK_NAME aligned.apk
$BT/apksigner verify --print-certs $FA/tools/$APK_NAME | head -3
ls -lh $FA/tools/$APK_NAME
echo "APK-OK"
