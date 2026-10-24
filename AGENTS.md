# Agents

Instructions for AI coding agents working in this repository.

The canonical rules are in [`CLAUDE.md`](CLAUDE.md) — read it before changing
anything. It applies to every agent, not only Claude; this file exists so tools
that look for `AGENTS.md` find it. The short version:

- **Change [`/shared`](shared/) first.** The wire format, state machine, pairing
  rules and design tokens live there, and both platforms are tested against it.
- **Every user-facing string exists in English and Persian.** On Windows the
  only string store is `windows/Relay.App/Strings.cs`; never add a `.resw`.
- **CI is the build system** ([ADR-0004](docs/adr/0004-github-only-build-and-release.md)).
  Push a branch; the pipeline builds, tests on real devices and releases.
- **Green is not the same as tested.** Keep the class list in
  `.github/scripts/device-tests.sh` current when adding a test that matters.
- **Nothing leaves the device.** No accounts, servers or telemetry; a new network
  call needs an ADR.

Start with [`docs/architecture.md`](docs/architecture.md), the
[ADRs](docs/adr/) and [`CONTRIBUTING.md`](CONTRIBUTING.md).
