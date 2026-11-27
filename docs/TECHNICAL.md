# Photo Compressor — technical notes

## What it does, precisely

1. Scans the gallery for JPEG photos larger than 200 KB.
2. Compresses them to 80% JPEG quality, in place.
3. Preserves EXIF metadata (orientation, capture date, camera model, GPS…): it is read before compression and
   restored afterwards, since `Bitmap.compress()` would otherwise strip it.
4. Skips photos where the gain would be less than 10%.
5. Shows how much space was freed and how it compares to the total storage.

## Implementation details

- Compression runs as a foreground service (WorkManager `CoroutineWorker`), so it survives the app being
  backgrounded on large libraries.
- Photos are processed in batches of 2000, which keeps each write-permission request well under Android's Binder
  transaction limit.
- Compression failures (including out-of-memory) are counted and shown to the user instead of being silently
  swallowed.
- Safe on low memory: uses `RGB_565` decoding and catches `OutOfMemoryError`.
- Crash and non-fatal error reporting via Firebase Crashlytics.

## Requirements

| | |
|---|---|
| Min Android | 8.0 (API 26) |
| Target Android | 15 (API 35) |
| Language | Kotlin 2.2 |
| UI | Jetpack Compose + Material 3 |

## Permissions

| Permission | When |
|---|---|
| `READ_MEDIA_IMAGES` | Android 13+ |
| `READ_EXTERNAL_STORAGE` | Android 8–12 |
| `WRITE_EXTERNAL_STORAGE` | Android 8–9 only |

On Android 11+, a system dialog asks the user to authorise overwriting existing photos before compression starts
(one dialog per batch of 2000 photos).

On Android 10 (API 29), write access to shared photos is restricted by scoped storage; affected files are counted
as errors and shown to the user rather than compressed.

## Build

```bash
git clone https://github.com/amandiofr/PhotoCompressor.git
cd PhotoCompressor
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Requires Android Studio Meerkat or later, or a local Android SDK with `ANDROID_HOME` set.

Also requires a `google-services.json` file in `app/` (Firebase Crashlytics), not included in the repo. Get your
own from the [Firebase console](https://console.firebase.google.com) (package name `com.amandiofr.photocompressor`)
and drop it in `app/google-services.json`; the build fails without it.

Release builds are signed and require `keystore.properties` + a keystore file at the repo root (also not included,
see `.gitignore`).

## Tech stack

- **Architecture**: MVVM, `AndroidViewModel` + `StateFlow`
- **UI**: Jetpack Compose, `AnimatedContent`, `Canvas` for the disk bar
- **Background work**: WorkManager `CoroutineWorker` running as a foreground service
- **Storage access**: `MediaStore` + `ContentResolver`
- **Write permission**: `MediaStore.createWriteRequest()` (API 30+), requested per batch
- **Metadata**: `androidx.exifinterface` to snapshot and restore EXIF around each compression
- **Crash reporting**: Firebase Crashlytics
- **Build**: AGP 8.13.2 · Gradle 8.13 · Compose BOM 2024.06.00
