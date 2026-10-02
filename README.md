# Alcove Workforce – Android App

Android app that opens **https://employees.alcoverealty.in/p/workforce-intelligence/login**
inside a secure WebView. GitHub Actions builds a **signed release APK** (not debug).

## Features
| Feature | Details |
|---|---|
| Login session saved | Cookies persist, user stays logged in |
| File upload | Camera + Gallery + Files for any `<input type="file">` |
| Downloads | PDF / Excel reports saved to Downloads folder |
| Location | Works if the website asks for GPS (attendance) |
| Pull to refresh | Swipe down at page top |
| Offline screen | Branded "Unable to connect" + Retry |
| External links | tel:, mailto:, WhatsApp, other sites open in phone apps |
| Back button | Goes to previous page; double-press to exit |
| Release build | R8 minified, signed, `apksigner` verified |

## One-time setup (5 minutes)
1. Create a **private** GitHub repo and push this whole folder (including `.github/`).
2. Go to **Actions → Generate Signing Keystore → Run workflow**.
3. Open the finished run, download artifact `signing-keystore-DELETE-AFTER-SAVING`.
4. Open `credentials.txt` and add 4 secrets in **Settings → Secrets and variables → Actions → New repository secret**:
   `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`.
5. Store `release.jks` + `credentials.txt` in a safe place (company drive / password manager), then **delete the artifact** from the run page.

> ⚠️ Always use the SAME keystore. A different key = phones cannot update the app, users must uninstall first.

## Building the APK
- Every push to `main` → **Actions → Build Release APK → Artifacts** → `AlcoveWorkforce-1.0.N.apk`
- Release version: `git tag v1.0.0 && git push origin v1.0.0` → APK attached to **Releases** page.
- Manual: Actions → Build Release APK → Run workflow.

`versionCode` auto-increments with every run, so new APKs install over old ones.

## Common changes
| Want to change | File |
|---|---|
| Start URL | `app/src/main/res/values/strings.xml` → `home_url` |
| App name | same file → `app_name` |
| Allowed in-app domains (e.g. SSO login) | `MainActivity.kt` → `INTERNAL_HOSTS` |
| Package ID | `app/build.gradle.kts` → `applicationId` |
| Icon | `app/src/main/res/mipmap-*` |

## Troubleshooting
| Problem | Fix |
|---|---|
| Workflow fails "Secret … is missing" | Do setup steps 2–4 |
| "App not installed" on phone | Old app was signed with another key → uninstall once |
| Login page opens in browser | Add the login domain to `INTERNAL_HOSTS` |
| Download does nothing | Site uses `blob:` download; serve the file via a normal URL |
