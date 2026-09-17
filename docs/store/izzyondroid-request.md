# IzzyOnDroid inclusion request — ready to post

**Where this goes:** IzzyOnDroid's issue tracker, which **moved to Codeberg**. Their
GitLab repository says so at the top of its README and is archived:

> "the issue tracker for the IzzyOnDroid repository has moved to our Codeberg
> presence. If you want to request the inclusion of an app … please do it over
> there!"

So the brief's `gh issue create --repo IzzyOnDroid/repo` cannot work: the project
is not on GitHub, and `gh` does not speak Codeberg. **This has to be posted by
hand**, or with a Codeberg API token.

**Caveat, stated because it matters:** `codeberg.org` is unreachable from the
machine this was written on — `curl` gets a connection timeout, not a 404 — so
**their issue template could not be read**. The content below is written against
their [Inclusion Policy](https://gitlab.com/IzzyOnDroid/repo/-/wikis/Inclusion-Policy),
which was readable and which lists what they need. If the template asks for
fields in a particular shape, reshape this to match it rather than posting it as
is.

---

## Title

```
Inclusion request: Relay (io.relay.app) — share an Android phone's internet with a Windows PC
```

## Body

```
### The app

**Relay** — https://github.com/Mahdi-mortazavi/relay

Relay shares an Android phone's internet connection with a Windows PC, over
Wi-Fi, the phone's hotspot, or a USB cable. Everything on the PC goes through
an encrypted WireGuard tunnel, not only the browser. No root, no account, no
server in the middle, and no VPN profile installed on the phone.

It is deliberately one half of a pair: the PC runs a companion app from the same
repository and the same build. The Android app on its own does nothing, and the
store description says so in its second paragraph rather than leaving someone to
discover it.

| | |
|---|---|
| applicationId | `io.relay.app` |
| Licence | **GPL-3.0-only** (`LICENSE`, and stated in README and CONTRIBUTING) |
| Current release | **2.8.9**, versionCode `20809` |
| minSdk / targetSdk | 26 / 37 |
| Source | GitHub, public, with a description and a populated issue tracker |
| Releases | APKs attached to GitHub tagged releases — your preferred arrangement |

### Which APK

`Relay-android-arm64-v8a.apk` — **7.7 MB**, well inside your 30 MB rule of
thumb.

A `Relay-android-universal.apk` (25 MB) is published alongside it for people
whose device rejects the arm64 one, and an `.aab` that is not directly
installable. Per your note about architectures, arm64 is the one to take; happy
to follow whatever you prefer.

### Checked against your inclusion policy

- **Free and open source** under an FSF/OSI licence: GPL-3.0-only.
- **Targeted at end users**, not a library or a demo.
- **Code freely accessible** at GitHub, with a repository description.
- **No proprietary components and no trackers at all.** The only third-party
  runtime dependency is `com.wireguard.android:tunnel` (GPL-2.0); the rest is
  this project's own code, including a Go library built from `/wg` in the same
  repository. There is no analytics, ads, crash-reporting or telemetry SDK of
  any kind — verifiable from `android/app/build.gradle.kts`.
- **No `android:debuggable`, no `android:testOnly`**, signed with a release key.
  From `apksigner verify --print-certs` on the published 2.8.9 APK:
  `CN=Relay, OU=Relay, O=Relay open-source project, C=US`,
  SHA-256 `d551778e2287ca4f0a4c0aa1b74aec9443669c05f015bb4c5535f952a7e19f3c`.
  (v2 scheme; v1 is unnecessary at minSdk 26.)
- **No `android:usesCleartextTraffic`.**
- **Unique packageName and display name.** Not a fork of anything.
- **Maintained.** Releases are frequent and each one carries written notes.
- **Descriptions and changelogs are in the repository** under
  `fastlane/metadata/android/{en-US,fa}/`, so you should not need to write any.

### The one point in your policy that deserves a direct answer

Your policy says an app

> "must not download additional executable binary files (e.g. addons,
> auto-updates, etc.) without explicit user consent. Consent means it needs to
> be opt-in …"

Relay does have an in-app updater, and it holds `REQUEST_INSTALL_PACKAGES`. I am
raising it rather than letting you find it.

**As of the commit below, a copy installed from an app repository never offers
its own updates.** `InstallSource` asks the platform which package installed
Relay, and when that is a repository client — `org.fdroid.fdroid`,
`org.fdroid.basic`, `com.looker.droidify`, `com.machiav3lli.fdroid`,
`nya.kitsunyan.foxydroid`, `dev.imranr.obtainium`, `com.android.vending` — the
update check does not run at all. So the copy **you** serve will not fetch a
binary, ever, and it will not nag anyone to leave your update channel.

A sideloaded copy still checks the GitHub releases page and shows a banner.
Installing is never automatic even then: it happens only if the person taps the
button, and Android's own installer asks them to confirm.

The list cannot be exhaustive — Android gives an app no way to ask whether an
installer is a store — so it fails toward *showing* the banner, and a unit test
pins that direction. If you would rather the app also carried an explicit
off-switch, or if you want additional installer ids in that list, say so and it
will be in the next release.

### Reproducible builds

Not yet, and I would rather say so than have you discover it. Full Mode's
forwarder is a Go library bound with `gomobile`, and the build currently uses
`GOTOOLCHAIN=auto`, no `-trimpath`, and an NDK taken from the environment — so
the native `.so` files are not byte-stable across machines or across time. That
is fixable and is written down as such; it is not a claim I am making today.

### Contact

Issues and private vulnerability reporting are both on the repository;
`SECURITY.md` has the policy, with a 7-day first response and a 30-day
assessment.

Thank you for the repo, and for the time it takes to review these.
```

---

## Before posting — worth deciding

1. **Which APK** to offer them. The recommendation above is arm64. If you would
   rather they took the universal one, change that section.
2. **Whether to mention the updater at all.** I think yes, plainly: they will see
   `REQUEST_INSTALL_PACKAGES` and an undisclosed self-updater is one of the
   things their policy lists as grounds for *removal*. Disclosing it with the fix
   already shipped is a much better first impression than being asked.
3. **Post after 2.9.0 ships**, so the installer-detection commit is in a
   published release rather than only on `main`. Linking a fix that is not yet in
   any APK invites a reviewer to check the APK and not find it.
