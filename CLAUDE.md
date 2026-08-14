# Relay — notes for a Claude Code session

Two apps and one shared contract. Android is Kotlin + Compose (`android/`),
Windows is .NET 8 + WinUI 3 (`windows/`), Full Mode's tunnel is Go (`wg/`) and
is used by both ends. `shared/` is the contract between them.

Start with [`docs/architecture.md`](docs/architecture.md) and the
[ADRs](docs/adr/). `CONTRIBUTING.md` has the rest.

## The rules that are easy to break by accident

**Change `/shared` first.** The wire format, the state machine, the pairing
rules and the design tokens live there, and both platforms are asserted against
it. Editing one platform to match the other, without moving the contract, is how
the two apps drift — and they have, twice, both times in ways no test caught.

**Every user-facing string exists in English *and* Persian.** On Windows that
means `windows/Relay.App/Strings.cs` — the only string store, enforced by
`StringsCoverageTests`. There used to be a second one nobody read, and six
strings were "added" to it; the app showed users raw keys like `CodeNoDevice`
for four releases. Do not reintroduce a `.resw`.

**CI is the build system (ADR-0004).** There is no local build step in the
normal loop: push, and the pipeline builds, tests on real devices, and can cut a
release. Do not add a "you must install X to work on this" requirement without
an ADR. (Testing on *your own* hardware is the deliberate exception — see
[`docs/local-device-testing.md`](docs/local-device-testing.md).)

**Green is not the same as tested.** A job that skips its only meaningful test
is also green. `device-tests.sh` names the classes that must have run and fails
if one silently skipped; keep that list current when adding a test that matters.

**Nothing leaves the device.** No accounts, no servers, no telemetry. A change
that adds a network call to anything but the user's own phone needs a very good
reason and an ADR.

## Where the truth is

| Question | File |
|---|---|
| What does a pairing code mean? | `shared/pairing-beacon.md` |
| What goes in the QR? | `shared/qr-payload.schema.json` |
| What are the legal states? | `shared/connection-states.json` |
| What does error X tell the user? | `docs/errors.md` |
| What has hardware still not proven? | `docs/testing.md` |
| How does a release get cut? | `docs/release.md` |

`shared/test-vectors.json` is consumed by the Android, Windows and Go suites at
once. Adding a vector there is usually worth more than adding a test to one side.

## Testing on real hardware

A phone plugged into the laptop, with the Windows app on that same laptop, tests
the one thing CI structurally cannot: two machines, one Wi-Fi, a firewall in its
default state. That runbook is [`docs/local-device-testing.md`](docs/local-device-testing.md),
and the `/device-test` skill drives it.
