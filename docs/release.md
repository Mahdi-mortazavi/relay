# Release process

Releases are **tag-triggered only** — an ordinary push can never publish anything.

**One tag, one release, both platforms.** `git push origin v1.7.0` runs
`release.yml`, which builds the signed Android artifacts and both Windows
installers and publishes them together, so
`/releases/latest/download/<asset>` resolves for every platform — which is what
the README download buttons point at.

| Tag | Workflow | Artifacts attached to the GitHub Release |
|---|---|---|
| `v*.*.*` | `release.yml` | `Relay-android-arm64-v8a.apk`, `Relay-android-universal.apk`, `Relay-android.aab`, `Relay-Setup-x64.exe`, `Relay-Setup-x86.exe` |

`release.yml` also takes a `workflow_dispatch` with a version and a `publish`
toggle. Leave `publish` off for a dry run; turn it on to cut the release without
pushing a tag — `gh release create` makes the tag itself, which is the only
route from an environment where pushing tags is blocked.

There is no per-platform release path, and deliberately so. `android-release.yml`
and `windows-release.yml` were removed: the Android one never built the Full Mode
library and never passed `-PrelayRequireWg`, so it could publish a signed release
of an app offering a mode that could not start, and it shipped no universal APK.
Both created releases under non-semver tags (`android-v1.7.1`), which GitHub can
promote to "Latest" — breaking every `releases/latest/download/…` link in the
README at once. **A tag that does not match `v*.*.*` does not release anything.**

Every release also carries **`SHA256SUMS.txt`**, computed in the publish job over
exactly the artifacts it is about to upload. Until the Windows installer is
code-signed it is the only integrity check a user has, so it is not optional:

```bash
sha256sum -c SHA256SUMS.txt          # Linux/macOS
Get-FileHash .\Relay-Setup-x64-<version>.exe -Algorithm SHA256   # Windows
```


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

**The signing pipeline is built, automatic, and currently idle.** `release.yml`
signs every installer with Authenticode and verifies the result — but only when
a certificate is present. Add these two secrets and the very next release is
signed, with no other change:

| Secret | Meaning |
|---|---|
| `WINDOWS_CERT_BASE64` | The `.pfx` code-signing certificate, base64-encoded |
| `WINDOWS_CERT_PASSWORD` | Its password |

```bash
base64 -w0 codesign.pfx    # Linux/macOS
[Convert]::ToBase64String([IO.File]::ReadAllBytes("codesign.pfx"))   # PowerShell
```

Until then the step emits a warning and ships unsigned, which is the honest
status quo rather than a failure: `SHA256SUMS.txt` is the integrity check, and
SmartScreen warns on first run ("Windows protected your PC" → *More info* →
*Run anyway*). It is also why we ship an EXE installer rather than MSIX —
unsigned MSIX will not install at all (ADR-0005).

**The pipeline will not self-sign, deliberately.** A self-signed certificate
still trips SmartScreen; all it would achieve is making an unsigned build look
signed to anyone reading the workflow. Only a certificate chaining to a trusted
root removes the warning.

Signatures are RFC-3161 timestamped against DigiCert, falling back to Sectigo
and GlobalSign. Timestamping is not optional: without it every signature becomes
invalid the day the certificate expires — including on copies already
downloaded.

### Getting a certificate

| Route | Cost | Notes |
|---|---|---|
| [SignPath Foundation](https://signpath.org/) | Free for OSS | Requires review; the usual path for a project like this |
| [Azure Trusted Signing](https://learn.microsoft.com/azure/trusted-signing/) | ~$10/month | Microsoft-run, designed for CI; needs a different signtool invocation than the PFX path above |
| OV certificate from a CA | ~$200–400/year | Works with the PFX path as written; SmartScreen reputation still builds over time |
| EV certificate | ~$300–600/year | Immediate SmartScreen reputation; usually requires a hardware token, which complicates CI |
