# Contributing to Relay

## The GitHub-only rule (read this first)

This project has **no local build loop**. All building, testing, and releasing happens exclusively in **GitHub Actions**:

- `ci.yml` builds both apps headlessly and runs all unit tests on every push and pull request. **"It works" means "CI is green"** — nothing else counts as verified.
- Release workflows are **tag-triggered only** (`android-v*.*.*`, `windows-v*.*.*`) and attach installable artifacts to a GitHub Release.
- To try a change on real hardware, download the debug artifacts uploaded by the CI run — never assume a contributor can build locally.

You *may* build locally if you have the toolchains (Android Studio / .NET 8 SDK + Windows App SDK), but nothing in this repository may **require** it, and no documentation may instruct it as the primary path.

## Workflow

- Work happens in phases (see `docs/roadmap.md`); **one pull request per phase** against `main`.
- Atomic, well-described commits — no monolithic "phase N" commits.
- Every architectural decision gets an ADR in `docs/adr/`.
- Contracts (QR payload, state machine, design tokens) live in `/shared` and change there first; both platforms follow in the same PR.
- No scope creep: ideas for later go to `docs/backlog.md`.

## Framing policy

Relay is documented and coded as a **general-purpose, local-only hotspot connection-sharing utility** — a networking tool. No identifier, comment, filename, or document may describe it as a tool for circumventing network filtering. Pull requests violating this are rejected regardless of technical quality.

## Privacy policy (code-level)

Local-only, zero telemetry. No analytics SDKs, no cloud crash reporting (opt-in only if ever added, default off), no network calls except the user's own traffic being relayed.

## Repository settings (manual — cannot be set in code)

The repository owner should configure these once in **GitHub → Settings**:

1. **Branches → Branch protection rule for `main`:**
   - Require a pull request before merging.
   - Require status checks to pass before merging; mark **`Android (build + test)`** and **`Windows (build + test)`** (the two `ci.yml` jobs) as **required checks**.
   - Require branches to be up to date before merging.
2. **Actions → General:** Workflow permissions → "Read and write permissions" (needed by release workflows to create GitHub Releases).
3. **Secrets and variables → Actions:** add the Android signing secrets documented in `docs/release.md` before cutting the first Android release.

## Code style

- Kotlin: official Kotlin style, Compose-first (no XML layouts).
- C#: .NET conventions, nullable reference types enabled.
- Strings are always localized (EN + FA) — no hardcoded user-facing text.
- Accessibility and RTL correctness are review criteria, not afterthoughts.
