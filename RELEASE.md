# Releasing ToDoApp

Distribution is **sideload from GitHub Releases** on the private `Kargone/ToDoApp` repo — not
the Play Store. Each release is a signed release **APK** attached to a GitHub Release; install it
by downloading the APK on the phone. (Play Store publishing is parked, not abandoned — see the
bottom of this file.)

> **Security:** the signing keystore, its passwords, and every SHA fingerprint are secrets.
> They live on your machine and in the Firebase console — **never** in this repo.
> `.gitignore` blocks `keystore.properties`, `*.jks`, `*.keystore`, and `*.apk`.

## 1. Create the signing keystore (one time)

Back it up off this machine — if you lose the `.jks` **and** its password, you can't ship an
update that installs over the existing app (a different signature won't upgrade in place).

```sh
keytool -genkeypair -v \
  -keystore "./todoapp-upload.jks" \
  -alias upload -keyalg RSA -keysize 2048 -validity 10000
```

(`keytool` ships with the JDK, e.g. `C:/Program Files/Java/jdk-17/bin/keytool.exe`.)
The `.jks` can live in the repo root — `*.jks` is gitignored, so it's never committed.

## 2. Point the build at it

Copy `keystore.properties.example` to `keystore.properties` (gitignored) and fill in the real
`storeFile`, `storePassword`, `keyAlias`, `keyPassword`. `storeFile` may be relative to the repo
root (`./todoapp-upload.jks`). Without this file, release builds are just left unsigned (so
clones/CI don't break).

## 3. Make Google Sign-In work in the release build (one time)

Sign-in validates the app by package + SHA-1, so the signing cert must be registered in Firebase.
**This SHA-1 goes in the Firebase console only — never into any file in this repo.**

1. Print the keystore's SHA-1:
   ```sh
   keytool -list -v -keystore ./todoapp-upload.jks -alias upload
   ```
2. **Firebase console → Project settings → Your Android app (`com.karson.todoapp`) → Add
   fingerprint** → paste the SHA-1.
3. **Re-download `google-services.json`** and replace `app/google-services.json`.
   (It may now include a `certificate_hash` — a SHA-1. Low sensitivity, but keep the repo
   **private**; if you ever make it public, consider gitignoring `google-services.json`.)

Already done for the current keystore — sign-in is verified working on the release build.

## 4. Build the signed release APK

```sh
.\gradlew.bat :app:assembleRelease
```

Output: `app/build/outputs/apk/release/app-release.apk` (signed). Verify the signature with:

```sh
"%LOCALAPPDATA%\Android\Sdk\build-tools\36.0.0\apksigner.bat" verify --print-certs app/build/outputs/apk/release/app-release.apk
```

## 5. Publish a GitHub Release

Bump `versionCode` (and usually `versionName`) in `app/build.gradle.kts` first for every new
build, then:

```sh
cp app/build/outputs/apk/release/app-release.apk ToDoApp-vX.Y.apk
gh release create vX.Y "ToDoApp-vX.Y.apk#ToDoApp-vX.Y.apk" \
  --repo Kargone/ToDoApp --title "ToDoApp X.Y" --notes "..."
```

## 6. Install on the phone

- The repo is **private**, so release assets download **only when signed into GitHub in the
  phone's browser**. Logged-out / incognito gets a 404.
- Open the release page, tap the `.apk` asset, then open the download. Android will ask to allow
  "Install unknown apps" for the browser/Files app the first time — allow it, then Install.

---

## Parked: Google Play publishing

Not in use (decided against — too much friction to make public). If you ever reconsider, the
pieces still exist: build an **AAB** with `.\gradlew.bat :app:bundleRelease`
(`app/build/outputs/bundle/release/app-release.aab`), opt into **Play App Signing**, and register
BOTH the app-signing and upload SHA-1s in Firebase. Store assets: 512×512 icon (export from
`play/store-icon-512.svg`), feature graphic, screenshots, descriptions, privacy-policy URL.
Note that Play re-signs with Google's key, so a Play install won't upgrade a sideloaded APK
in place (different signature) and vice-versa.
