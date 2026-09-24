# FormMitra Android App

Native Android client for **FormMitra** (https://formmitra-git-main-webbuilder1.vercel.app/) —
separate product from BrowseAgent (separate package, keystore, repo, backend).

- **Package:** `com.formmitra.app`
- **What it is:** WebView shell around the FormMitra website with native bottom nav
  (Home, Tracking, Jobs, Agent, Profile), update checker, and background digest
  notifications (WorkManager, every 6h, battery-aware).
- **What it is NOT:** no automation engine, no accessibility service, no form-filling.
  Login session is shared with the website via WebView cookies — login on web = login in app.

## Build

Manual toolchain (no Gradle): `tools/build-apk.sh` is the source of truth for
`VERSION_CODE` / `VERSION_NAME`.

```bash
bash app-android/tools/build-apk.sh
# output: app-android/tools/formmitra-v1.apk (name per version)
```

Requires the shared read-only toolchain at `~/workspace/phone-agent/tools`
(android-sdk, jdk-17, kotlinc, deps) — see script header.

## Signing

The release keystore (`tools/formmitra-release.keystore`) is **NOT** in this repo —
it lives with the maintainer. First build without it generates a new key;
updates require the SAME key, so keep it safe.

## Publish flow

1. Build APK → 2. copy to `public/app/formmitra-v1.apk` in `raj3kk/formmitra` →
   push (Vercel serves it) → 3. `/api/app/version` on the site points at it.
