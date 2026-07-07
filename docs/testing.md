# Testing

## Automated (runs in CI — the only build system)

- **Unit tests** for all core logic: QR payload encode/decode/validation (against [`/shared/test-vectors.json`](../shared/test-vectors.json)), connection state-machine transitions (against [`/shared/connection-states.json`](../shared/connection-states.json)), and — from Phase 1 — proxy snapshot/rollback with a mocked OS layer.
- Android tests run via Gradle on `ubuntu-latest`; Windows tests via `dotnet test` on `windows-latest`. Both are required checks on every PR.
- Coverage target: meaningful coverage of core logic; no vanity percentage on UI glue.

## Manual test matrix (real hardware, CI artifacts only)

There is no local dev loop: manual verification always uses an **installable artifact from a CI run or Release** (APK / Windows installer). Each phase adds its acceptance criteria here with a step-by-step check.

> Populated starting Phase 1.

| AC | How to verify | Artifact | Status |
|---|---|---|---|
| _(Phase 1 pending)_ | — | — | — |
