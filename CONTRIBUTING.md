# Contributing to Relay

Thanks for being here. This file is short on ceremony and long on the things
that actually get pull requests merged.

## Start in five minutes

You do **not** need Android Studio, the .NET SDK, or Go installed. There is no
local build loop — CI is the build system ([ADR-0004](docs/adr/0004-github-only-build-and-release.md)).

```bash
git clone https://github.com/Mahdi-mortazavi/relay.git
cd relay
git switch -c my-change
# edit, commit, push — the pipeline builds it and tests it on real devices
```

Push a branch, open a pull request, and CI will build both apps, run every unit
test, install the real app on real Android images and drive it through its own
UI. **"It works" means "CI is green"** — nothing else counts as verified.

Want a place to start? Issues labelled
[**`good first issue`**](https://github.com/Mahdi-mortazavi/relay/labels/good%20first%20issue)
are scoped so that you can finish one without having read the whole codebase.
[`ROADMAP.md`](ROADMAP.md) says what is wanted next and why — the **Linux
client** in particular is the single biggest thing one contributor could add.

To try a change on real hardware, download the debug artifacts from the CI run.
Never assume a contributor can build locally.

## The four rules that are easy to break by accident

These are not style preferences. Each one is here because it went wrong once.

**1. Change `/shared` first.** The wire format, the state machine, the pairing
rules and the design tokens live in [`/shared`](shared/), and both platforms are
asserted against them. Editing one platform to match the other, without moving
the contract, is how the two apps drift — and they have, twice, both times in
ways no test caught. Both platforms follow in the same PR.

**2. Every user-facing string exists in English *and* Persian.** On Windows that
means `windows/Relay.App/Strings.cs`, which is the only string store and is
enforced by `StringsCoverageTests`. There used to be a second one nobody read,
and the app showed users raw keys like `CodeNoDevice` for four releases.

**3. Green is not the same as tested.** A job that skips its only meaningful
test is also green. The test names that matter are pinned in the workflow and in
`device-tests.sh`, and CI fails if one silently skips. **If you add a test that
matters, add its name to that list.**

**4. Nothing leaves the device.** No accounts, no servers, no telemetry, no
analytics, no cloud crash reporting. A change that adds a network call to
anything but the user's own phone needs a very good reason and an ADR.

## Workflow

- One pull request per coherent change, against `main`.
- Atomic, well-described commits. Write *why*, not *what* — the diff already
  says what.
- Every architectural decision gets an ADR in [`docs/adr/`](docs/adr/). Read a
  couple before writing your first; they explain the reasoning, not just the
  outcome.
- Ideas for later go to [`docs/backlog.md`](docs/backlog.md) rather than into
  the current PR.

## Where the truth is

| Question | File |
|---|---|
| How does the whole thing fit together? | [`docs/architecture.md`](docs/architecture.md) |
| What does a pairing code mean? | [`shared/pairing-beacon.md`](shared/pairing-beacon.md) |
| What goes in the QR? | [`shared/qr-payload.schema.json`](shared/qr-payload.schema.json) |
| What are the legal states? | [`shared/connection-states.json`](shared/connection-states.json) |
| What does error X tell the user? | [`docs/errors.md`](docs/errors.md) |
| What has hardware still not proven? | [`docs/testing.md`](docs/testing.md) |
| How does a release get cut? | [`docs/release.md`](docs/release.md) |

[`shared/test-vectors.json`](shared/test-vectors.json) is consumed by the
Android, Windows and Go suites at once. **Adding a vector there is usually worth
more than adding a test to one side.**

## Code style

- **Kotlin:** official Kotlin style, Compose-first — no XML layouts.
- **C#:** .NET conventions, nullable reference types enabled.
- **Go:** `gofmt`, and errors that say what a person should do about them.
- Strings are always localized (EN + FA). No hardcoded user-facing text.
- Accessibility and RTL correctness are review criteria, not afterthoughts.

## Framing policy

Relay is documented and coded as a **general-purpose, local-only connection
sharing utility** — a networking tool. No identifier, comment, filename or
document may describe it as a tool for circumventing network filtering. Pull
requests violating this are rejected regardless of technical quality.

## Reporting a security issue

Please do not open a public issue. [`SECURITY.md`](SECURITY.md) has the process.

## Licensing of contributions

Relay is licensed under the **[GNU General Public License v3.0](LICENSE)**. By
submitting a pull request you agree that your contribution is licensed under the
same terms, and that you have the right to submit it.

If you distribute a modified version of Relay, GPL-3.0 requires you to make your
source available under the same license. That is deliberate: this is a tool
people rely on for their own connection, and they should always be able to see
what it does and build it themselves.

## Repository settings (owner only)

These cannot be set in code and are configured once in **GitHub → Settings**:

1. **Branches → protection rule for `main`:** require a pull request; require
   status checks (`Android (build + test)`, `Windows (build + test)`); require
   branches to be up to date.
2. **Actions → General:** workflow permissions set to *Read and write*, needed
   to create Releases.
3. **Secrets and variables → Actions:** the Android signing secrets documented
   in [`docs/release.md`](docs/release.md).
