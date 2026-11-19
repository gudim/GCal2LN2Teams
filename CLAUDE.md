# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

An Android app (Kotlin Multiplatform, Compose) that copies calendar events between **Google Calendar**, **HCL Domino / Lotus Notes** (Domino REST API) and **Microsoft Teams / Outlook** (MS Graph). Everything runs on the phone. Docs, comments, log messages and UI strings are in Russian (UI also has English in `values-en`); keep that when you edit them.

The desktop Python version (web UI, `src/`, pytest) was removed; its history is in git. Do not resurrect it.

## Layout

- `mobile/` — the Gradle project. `shared/` holds the sync engine, adapters and Compose UI (`commonMain`); `androidApp/` holds Android-only code (auth via Custom Tabs + loopback server, WorkManager background sync, DataStore settings, Keystore secrets). See `mobile/README.md`.
- `spec/golden/*.json` — acceptance scenarios for the engine, checked by `GoldenPlansTest`. The Kotlin engine is the reference now (ADR 0005): to change behaviour, update `expected` from `runGolden` output and review the diff.
- `spec/fixtures/` — API responses used by adapter tests.
- `docs/adr/` — decisions (conflicts and deletes, mobile architecture, time twins, auto-apply, adopting unlinked copies). `GLOSSARY.md` — terms (Вопрос, Фоновая синхронизация, Автоприменение, …).

## Commands (from `mobile/`)

```bash
./gradlew :shared:jvmTest                 # engine, adapters, UI models, golden scenarios
./gradlew :androidApp:testDebugUnitTest   # Android platform layer
./gradlew :androidApp:assembleDebug       # debug APK
./gradlew :androidApp:assembleRelease     # signed if release.* keys are in local.properties
```

`mobile/local.properties` (not in git) holds `sdk.dir` and the release signing keys.

## Releasing

Bump `versionCode`/`versionName` in `mobile/androidApp/build.gradle.kts` and `SHARED_VERSION` in `shared/.../Version.kt` (plus `SmokeTest`), run the tests, `assembleRelease`, then publish the APK as a GitHub release `vX.Y.Z` named `gcalsync-X.Y.Z.apk`. The app's weekly update check reads `/releases/latest`.
