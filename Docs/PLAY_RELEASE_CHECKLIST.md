# Echo — Google Play launch checklist (v1.0.0)

## ✅ Done in the codebase
- `versionCode = 1`, `versionName = "1.0.0"` (app/build.gradle.kts)
- Release build: R8 minify + resource shrinking + ProGuard keep rules; **R8 release
  build verified to compile** cleanly.
- Models shipped as install-time **asset packs** → the upload artifact is the **AAB**
  (`app/build/outputs/bundle/release/app-release.aab`), keeping the base module under
  Play's size ceiling.
- No hardcoded API keys in source. `keystore.properties`, `*.jks`, `*.keystore` are
  gitignored and untracked.
- Removed unused **BLUETOOTH / BLUETOOTH_CONNECT** permissions (avoids a "Nearby devices"
  Play declaration for a feature that doesn't exist).
- Release build **strips debug/info logs** (they printed personal content to logcat).
- targetSdk 36 / compileSdk 37 (current).

## 🔑 You must do (sensitive or Console-only — I can't)

1. **Secure your upload key.** `app/echo-release-key.jks` signs the release. Back it up
   somewhere safe and private. Consider a stronger password than the current one. If you
   ever lose it, Play App Signing lets you request an upload-key reset — but keep it safe.

2. **Firebase SHA-1 (critical for Google Sign-In in production).**
   - Enroll in **Play App Signing** (default for new apps).
   - Copy the **App signing key SHA-1** (and SHA-256) from Play Console → Setup → App
     signing, AND the **upload key** SHA-1 (`keytool -list -v -keystore app/echo-release-key.jks`).
   - Add BOTH SHA-1s to your Firebase project (Project settings → your Android app), then
     download the refreshed `google-services.json`. Without the Play app-signing SHA-1,
     Google Sign-In fails for users who install from Play.

3. **Host the privacy policy.** Fill in the placeholders in `docs/PRIVACY_POLICY.md`,
   publish it at a public URL, and paste that URL into Play Console.

4. **Play Console app setup:** create the app, then complete
   - **Data safety** form: on-device by default; email + Google user-id for auth
     (Firebase); microphone/photos/calendar used on-device; content sent to a third-party
     AI provider **only when the user supplies their own key**; approximate location for
     weather; no data sold; encrypted in transit.
   - **Content rating** questionnaire, **Target audience** (not children), **Ads = No**.
   - **Store listing:** app name, short/full description, icon (512×512), feature graphic,
     phone screenshots, category, contact email.

5. **Sensitive-permission declarations in Console:**
   - **Location** (ACCESS_COARSE_LOCATION) — for weather. Needs the Location permission
     declaration + prominent disclosure, or **drop the weather feature** to skip it (see
     product decision below).
   - **USE_EXACT_ALARM** — allowed for reminder/alarm apps; declare its use (reminders).
   - **READ_CALENDAR**, mic, camera, photos — reflect in Data safety.

6. **Release track:** upload the AAB to **Internal testing** first, click through the whole
   app on a Play-installed build (verifies Google Sign-In + app-signing SHA-1), then
   promote to Production.

## ⚠️ Recommended before upload
- **Clean-install smoke test of the release (R8) build.** The builds tested during
  development were debug (no R8). A final install of the *release* build catches any
  reflection/serialization stripped by R8 at runtime. Because it's signed differently from
  the debug build, it requires an uninstall (which clears local data) OR a temporary
  `applicationIdSuffix` so it installs alongside without touching your data.

## 🤔 Product decisions to confirm
- **Weather on Home** keeps the location permission. Keep it (declare location) or drop it
  for a lighter-review v1?
- **Sign-in providers**: currently email + Google (Facebook was dropped for v1).
