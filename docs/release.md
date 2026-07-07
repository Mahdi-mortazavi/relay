# Release process

Releases are **tag-triggered only** — an ordinary push can never publish anything.

| Platform | Tag | Workflow | Artifacts attached to the GitHub Release |
|---|---|---|---|
| Android | `android-v*.*.*` | `android-release.yml` | `relay-arm64-v8a-<version>.apk` (signed, primary sideload artifact) + `relay-<version>.aab` |
| Windows | `windows-v*.*.*` | `windows-release.yml` | `Relay-Setup-x64-<version>.exe` + `Relay-Setup-x86-<version>.exe` |

Each release body gets an auto-generated changelog (commits since the previous tag of the same platform) plus a short "How to install" note.

## Android signing

The release workflow signs with a keystore held only in GitHub Secrets:

| Secret | Meaning |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | The keystore file, base64-encoded |
| `ANDROID_KEYSTORE_PASSWORD` | Keystore password |
| `ANDROID_KEY_ALIAS` | Key alias |
| `ANDROID_KEY_PASSWORD` | Key password |

**Generating the keystore (no local JDK required):** a manually-triggered workflow (`generate-keystore.yml`, added with the Android release pipeline in Phase 1) runs `keytool` on a CI runner and prints the base64 keystore + generated passwords **once**, masked, for the owner to copy into the secrets above. The keystore is never committed. Losing it means future APKs can't update over old installs — store a copy in a password manager.

## Windows signing

We currently have **no code-signing certificate**, so Windows SmartScreen may warn on first run of the installer ("Windows protected your PC" → *More info* → *Run anyway*). This is a known trade-off of unsigned distribution, not a bug. Buying an OV/EV code-signing certificate and signing in CI would remove the warning; tracked in `docs/backlog.md`. This is also why we ship an EXE installer rather than MSIX — unsigned MSIX won't install at all (see ADR-0005).
