# 📷 Photo Compressor

A simple Android app that compresses JPEG photos directly on the device — no cloud, no backup, no fuss.

Designed for non-technical users who are running out of storage space.

---

## What it does

1. **Scans** your gallery for JPEG photos larger than 200 KB
2. **Compresses** them to 80% JPEG quality in-place
3. **Skips** photos where the gain would be less than 10%
4. **Shows** how much space was freed and how it compares to your total storage

Photos are replaced in place. There is no undo. The quality reduction is invisible to the naked eye for typical camera photos.

---

## Features

- Single-screen UI — tap once to scan, tap again to compress
- Disk space bar: used / freed / available at a glance
- Live progress during compression
- Compression runs as a foreground service (WorkManager `CoroutineWorker`), so it survives the app being backgrounded on large libraries
- Photos are processed in batches of 2000 — keeps each write-permission request well under Android's Binder transaction limit
- Skips already-optimised photos gracefully
- Compression failures (including out-of-memory) are counted and shown to the user instead of being silently swallowed
- Safe on low memory: uses `RGB_565` decoding, catches `OutOfMemoryError`
- Crash and non-fatal error reporting via Firebase Crashlytics

---

## Requirements

| | |
|---|---|
| Min Android | 8.0 (API 26) |
| Target Android | 15 (API 35) |
| Language | Kotlin 2.2 |
| UI | Jetpack Compose + Material 3 |

---

## Permissions

| Permission | When |
|---|---|
| `READ_MEDIA_IMAGES` | Android 13+ |
| `READ_EXTERNAL_STORAGE` | Android 8–12 |
| `WRITE_EXTERNAL_STORAGE` | Android 8–9 only |

On Android 11+, a system dialog asks the user to authorise overwriting existing photos before compression starts (one dialog per batch of 2000 photos).

---

## Build

```bash
# Clone
git clone https://github.com/amandiofr/PhotoCompressor.git
cd PhotoCompressor

# Build debug APK
./gradlew assembleDebug

# Install on connected device
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Requires Android Studio Meerkat or later, or a local Android SDK with `ANDROID_HOME` set.

Also requires a `google-services.json` file in `app/` (Firebase Crashlytics) — not included in the repo. Get your own from the [Firebase console](https://console.firebase.google.com) (package name `com.amandiofr.photocompressor`) and drop it in `app/google-services.json`; the build fails without it.

Release builds are signed and require `keystore.properties` + a keystore file at the repo root (also not included — see `.gitignore`).

---

## Tech stack

- **Architecture**: MVVM — `AndroidViewModel` + `StateFlow`
- **UI**: Jetpack Compose, `AnimatedContent`, `Canvas` for the disk bar
- **Background work**: WorkManager `CoroutineWorker` running as a foreground service
- **Storage access**: `MediaStore` + `ContentResolver`
- **Write permission**: `MediaStore.createWriteRequest()` (API 30+), requested per batch
- **Crash reporting**: Firebase Crashlytics
- **Build**: AGP 8.13.2 · Gradle 8.13 · Compose BOM 2024.06.00

---

## Limitations

- JPEG only — PNG, HEIC and videos are not processed
- No backup — originals are overwritten
- On Android 10 (API 29), write access to shared photos is restricted by scoped storage; affected files are counted as errors and shown to the user rather than compressed
