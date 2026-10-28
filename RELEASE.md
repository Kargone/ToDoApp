# Releasing ToDoApp to Google Play

> **Security:** the upload keystore, its passwords, and every SHA fingerprint are secrets.
> They live on your machine and in the Play / Firebase consoles — **never** in this repo.
> `.gitignore` already blocks `keystore.properties`, `*.jks`, and `*.keystore`.

## 1. Create the upload keystore (one time)

Keep it **outside** the repo and back it up — losing it means contacting Play support to reset.

```sh
keytool -genkeypair -v \
  -keystore "./todoapp-upload.jks" \
  -alias upload -keyalg RSA -keysize 2048 -validity 10000
```

(`keytool` ships with the JDK, e.g. `C:/Program Files/Java/jdk-17/bin/keytool.exe`.)

## 2. Point the build at it

Copy `keystore.properties.example` to `keystore.properties` (gitignored) and fill in the real
`storeFile` path, `storePassword`, `keyAlias`, `keyPassword`. The build reads it automatically;
without it, release builds are just left unsigned (so clones/CI don't break).

## 3. Build the release bundle

```sh
.\gradlew.bat :app:bundleRelease
```

Output: `app/build/outputs/bundle/release/app-release.aab` — upload this `.aab` to Play.
(Current version: `versionCode 1`, `versionName "1.0"` in `app/build.gradle.kts`. Bump
`versionCode` for every new upload.)

## 4. Play Console

1. Create the app (package `com.karson.todoapp`), opt into **Play App Signing** (default).
2. Create an internal-testing release and upload the `.aab`.
3. Google now holds the *app signing key*; your `.jks` is the *upload key*.

## 5. Make Google Sign-In work in the release build

Sign-in validates the app by package + SHA-1, so the release fingerprints must be registered.
**These SHA-1 values go into the consoles only — do not paste them into any file in this repo.**

1. In **Play Console → Test and release → App integrity → App signing**, copy BOTH SHA-1s:
   the **app signing key** and the **upload key**.
2. In **Firebase console → Project settings → Your Android app**, add both SHA-1 fingerprints.
3. **Re-download `google-services.json`** and replace `app/google-services.json`.
   - Note: the refreshed file may now include `certificate_hash` entries. That is a SHA-1.
     It is low-sensitivity (a public cert fingerprint), but keep the repo **private**; if you ever
     make it public, consider gitignoring `google-services.json` instead.

## 6. Store listing assets

- **App icon:** 512×512 32-bit PNG. Export from `play/store-icon-512.svg` (the Graphite mark).
- Feature graphic (1024×500), phone screenshots, short + full description.
- A privacy policy URL is required (the app collects a Google account + task data via Firebase).

## 7. Future releases

Bump `versionCode` (and usually `versionName`), rebuild `bundleRelease`, upload. Same upload key.
