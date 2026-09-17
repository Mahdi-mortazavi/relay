# Store listing status

Where Relay stands in each open-source distribution channel, and what the next
move is. **Re-usable:** update the Status column after each change rather than
rewriting the file.

Last reviewed: **2026-09-17**, against Relay **2.8.9** (`versionCode 20809`).

| Channel | Mechanism | Status | Next move |
|---|---|---|---|
| **Obtainium** | No submission. Reads tagged GitHub releases. | ✅ **Live** | None. Link is in both READMEs. |
| **Komi Store** | No submission. Daily automated index of GitHub releases. | ✅ **Discoverable now** | Optional: four topics, worth +23 Android / +33 Windows. Not applied. |
| **IzzyOnDroid** | Hosts the developer's own signed APK. Request by issue. | 📝 **Request written, not sent** | Needs a human: their tracker is on Codeberg, unreachable from here. |
| **F-Droid (official)** | Builds from source in their buildserver. | ⛔ **Not ready** | Three toolchain problems first. See below. |
| RepoStore / OpenAPK / Droid-ify / Neo Store | Clients and aggregators, not destinations. | — | Nothing to do. They follow from the above. |

---

## What the app already satisfies

Measured, not assumed — from `aapt2 dump badging` and `apksigner verify` on the
published 2.8.9 APK, and from the build files.

| | |
|---|---|
| `applicationId` | `io.relay.app` |
| Licence | **GPL-3.0-only** — `LICENSE`, README and CONTRIBUTING agree |
| minSdk / targetSdk | 26 / 37 |
| Signing | release key, v2 scheme, `CN=Relay, O=Relay open-source project` |
| | cert SHA-256 `d551778e2287ca4f0a4c0aa1b74aec9443669c05f015bb4c5535f952a7e19f3c` |
| `debuggable` / `testOnly` | neither |
| `usesCleartextTraffic` | not set |
| Trackers | **none.** Only `com.wireguard.android:tunnel` (GPL-2.0) plus this repo's own Go AAR |
| Size | arm64 APK **7.7 MB**, well inside IzzyOnDroid's 30 MB rule of thumb |
| Release assets | APKs attached to `vX.Y.Z` tags — the arrangement IzzyOnDroid prefers |
| Store text | `fastlane/metadata/android/{en-US,fa}/`, within every F-Droid length limit |

## Obtainium — done

Tags are `vX.Y.Z` and asset names have been identical across releases, so the
automatic version detection has nothing to trip over.

One thing worth knowing: Obtainium's GitHub source collects **every** asset in a
release and does no `.apk` filtering of its own (`lib/app_sources/github.dart`).
A Relay release has six files, so a user sees the two APKs alongside two `.exe`
installers, an `.aab` and `SHA256SUMS.txt`. Both READMEs now say which one to
take. Pre-filtering via an Obtainium app-config link is possible but its JSON
schema was not verified, so it was not guessed at.

## Komi Store — nothing to submit

Their index is a daily GitHub Actions job
([`komi-store/komi-store-backend-data`](https://github.com/komi-store/komi-store-backend-data)),
not a form and not a curated list. Relay matches their Android and Windows
discovery queries today: 189 stars, pushed today, not archived, `.apk` and
`.exe` in tagged releases, topic `android`, primary language Kotlin.

- **New Releases** — eligible now, both platforms.
- **Trending** — in the candidate pool via their highest-weight spec.
- **Most Popular** — `stars:>5000`, hard-coded. Not close.

The only real lever is four truthful topics.
[`docs/store/komi-store-outreach.md`](docs/store/komi-store-outreach.md) has the
arithmetic, the one-line command, and the one topic that would score well and
was left out because it is not quite true.

## IzzyOnDroid — written, needs you to send it

[`docs/store/izzyondroid-request.md`](docs/store/izzyondroid-request.md) is ready
to post.

**Two things the brief got wrong**, both confirmed from their own pages:

1. Their tracker **moved to Codeberg**; the GitLab repo is archived. So
   `gh issue create --repo IzzyOnDroid/repo` cannot work — wrong host, wrong
   forge, and `gh` does not speak Codeberg.
2. `codeberg.org` is **unreachable from this machine** (connection timeout, not
   404), so their issue template could not be read. The request is written
   against their [Inclusion Policy](https://gitlab.com/IzzyOnDroid/repo/-/wikis/Inclusion-Policy),
   which was readable. Reshape it if the template asks for a different form.

**The policy point that needed work**, and now has it: they forbid fetching
executable binaries without opt-in consent. Relay has an in-app updater and holds
`REQUEST_INSTALL_PACKAGES`. Since `InstallSource` landed, a copy installed by a
repository client never runs the update check at all — so the copy they serve
never fetches a binary. The request says this plainly rather than waiting to be
asked.

**Recommended:** send it after the release that contains `InstallSource`, so a
reviewer checking the APK finds the behaviour the request describes.

## F-Droid — not ready, and it is the AAR

Metadata draft: [`docs/store/fdroid-metadata.io.relay.app.yml`](docs/store/fdroid-metadata.io.relay.app.yml).
Everything except `Builds:` is grounded — `Categories` and the absence of
`AntiFeatures` were taken from fdroiddata's own `config/categories.yml` and
`config/antiFeatures.yml`, not from memory. There is no AntiFeature for a
self-updater in their vocabulary, so it is documented in a comment instead.

F-Droid builds from source, and Full Mode's forwarder is a Go library bound to
an AAR by `scripts/build-wg-aar.sh`. The AAR is not committed, so a plain Gradle
build produces an app that honestly reports Full Mode as unavailable. Three
things block a recipe, and none can be fixed by writing YAML:

1. `GOTOOLCHAIN=auto` — the Go compiler version is not pinned, and `gomobile`
   installs its own `gobind` at `@latest` internally. A build whose compiler is
   chosen at build time is not reproducible and may not run in a restricted
   buildserver at all.
2. No `-trimpath` — absolute build paths are embedded in the `.so` files.
   Different checkout directory, different bytes.
3. The NDK comes from `ANDROID_NDK_LATEST_HOME` rather than a pinned version,
   and the linker's version affects the output.

Note that F-Droid does **not** require reproducibility to include an app — it
only requires that they can build it, and they sign with their own key.
Reproducibility is what would let them publish *this project's* signature
instead. But (1) is a build-reliability problem regardless of that, so it is
worth fixing on its own merits.

**Order that wastes nobody's time:** IzzyOnDroid now; F-Droid after items 1–3.
A recipe that fails in their buildserver gets the request closed.

---

## Signing, noted in passing

The published APKs carry the **v2 signature scheme only** — no v3. v1 is
unnecessary at minSdk 26, but v3 is what makes key rotation possible. For an app
that updates itself, having no rotation path means a lost or leaked key has no
recovery. Out of scope for store listings; recorded so it is not rediscovered.
