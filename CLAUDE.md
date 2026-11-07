# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

`AGENTS.md` is the single source of truth for architecture, hard constraints,
toolchain pinning rationale and hard-won platform lessons. It is imported below;
update it (not this file) when the design changes. Its "Current editor" section
supersedes the historical milestone notes further down in it.

@AGENTS.md

## Running Gradle here

The Homebrew JDK is keg-only, so every Gradle invocation needs it explicitly:

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@21 PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH
```

```sh
./gradlew test ktlintCheck                                   # JVM tests (debug + release variants) and lint
./gradlew :app:testDebugUnitTest --tests 'com.pdfeditor.app.domain.DocumentEditsTest'   # one JVM test class
./gradlew :app:testDebugUnitTest --tests '*DocumentEditsTest.someMethod'                # one JVM test method
./gradlew :app:connectedDebugAndroidTest                     # instrumented UI tests (emulator required)
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.pdfeditor.app.ui.reader.MultipageEditorTest
./gradlew assembleRelease                                    # minified, unsigned release APK; re-check size vs 15MB budget
```

If a build sits idle at `compileDebugKotlin` (the Kotlin compile daemon failing to
connect has hung builds here), add `-Pkotlin.compiler.execution.strategy=in-process`.

JVM tests run under Robolectric (`app/build.gradle.kts` opens JDK modules for
it). Multi-touch and long-press-drag can't be driven by `adb shell input`. Use
Compose `performTouchInput` in `androidTest` instead (see `WritingGesturesTest`,
`PageOverviewScreenTest`).

## Releases and validation records

- Bump `versionCode`/`versionName` in `app/build.gradle.kts`. Releases are
  signed with one consistent key (not in the repo) and published as
  `PDF-Editor-v<version>.apk` on GitHub with a SHA-256 checksum.
- Each release or significant feature gets a `docs/validation/<date>-<topic>.md`
  record of what was actually tested and on what (currently an API 35 emulator).
  Keep README "Limits" and those records honest: don't claim OCR, physical-device,
  API 26 runtime or arbitrary-script coverage that wasn't exercised.
- `docs/superpowers/plans/` holds past implementation plans; `reports/` and
  `research_notes/` hold architecture-review background, not current spec.
