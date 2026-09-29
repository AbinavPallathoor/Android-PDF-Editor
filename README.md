# PDF Editor

A minimalist, gesture-first Android PDF reader and light editor. The PDF is
the only thing on screen — dark, no toolbars, no chrome. Every interaction
(open, zoom, mode switch, undo/redo, page reorder) is a gesture with a
physics-based animation behind it.

100% free, open source, and offline: no ads, no accounts, no analytics, no
network access of any kind (the app does not declare the `INTERNET`
permission), and no proprietary dependencies (no Google Play Services, no
Firebase). See [`THIRD_PARTY_LICENSES`](THIRD_PARTY_LICENSES) for the full
dependency list — everything is Apache-2.0, MIT, or BSD.

## Status

Under active development, milestone by milestone. See
[`CLAUDE.md`](CLAUDE.md) for the current architecture and milestone list.

## Requirements

- JDK 17+ (the project builds and has been verified against Temurin/OpenJDK 21)
- Android SDK with platform 36 and build-tools 35.0.0+ installed
- min SDK 26 (Android 8.0), target/compile SDK 36 (Android 16)

## Building

```sh
./gradlew assembleDebug
```

## Running on an emulator or device

```sh
./gradlew installDebug
adb shell am start -n com.pdfeditor.app/.MainActivity
```

## Testing

```sh
./gradlew test                 # JVM unit tests (Robolectric-backed where needed)
./gradlew connectedAndroidTest # instrumented tests, requires a running emulator/device
```

## Linting / formatting

```sh
./gradlew ktlintCheck
./gradlew ktlintFormat
```

## License

Apache License 2.0 — see [`LICENSE`](LICENSE).
