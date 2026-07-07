# ADR-0004: GitHub Actions is the only build, test, and release system

**Status:** Accepted · **Date:** 2026-07-07

## Context

The developer machine has no Android/Kotlin toolchain and no Windows/.NET build environment, and this will not change. Any workflow that assumes local builds is unusable here.

## Decision

- **`ci.yml`** builds both apps headlessly and runs all unit tests on every push/PR: Android (Gradle) on `ubuntu-latest`, Windows (.NET/WinUI 3) on `windows-latest`. Debug artifacts are uploaded from every run so a testable build is always one click away.
- **Releases are tag-triggered only:** `android-v*.*.*` → signed arm64-v8a APK + AAB; `windows-v*.*.*` → x64 + x86 installers. Artifacts attach to a GitHub Release with an auto-generated changelog.
- Android release signing uses a keystore stored in GitHub Secrets; the keystore itself is generated once **by a manually-triggered CI workflow** (no local JDK exists to create one).
- "Verified" in this project always means: **the named CI run is green** and/or the acceptance criterion was checked on real hardware using an **installable CI artifact**.

## Consequences

- CI must stay green at all times — it is the build system, not a convenience check.
- Everything must build on stock GitHub runner images; no dependency may require a locally-provisioned SDK or license acceptance that can't be scripted.
- Windows artifacts are unsigned initially; SmartScreen warnings are a documented trade-off (see `docs/release.md`), removable later with a code-signing certificate.
