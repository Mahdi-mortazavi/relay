# ADR-0003: Foreground service + battery-exemption onboarding on Android

**Status:** Accepted · **Date:** 2026-07-07

## Context

The number-one failure of existing tools is the proxy silently dying minutes after screen-off, because they run as ordinary background components that Doze/battery savers kill (problem P1).

## Decision

- Sharing runs inside a **foreground service** with a persistent notification from the moment sharing starts. The notification shows live status and a one-tap Stop action.
- First-run onboarding explains and requests the **battery-optimization exemption** with a deep link to the exact settings screen; the app degrades gracefully (with a visible warning) if the user declines.
- The service returns `START_STICKY` (or the platform's current equivalent) and holds a **partial WakeLock only while transfer is active** — never permanently.
- Service lifecycle is driven by the shared connection state machine; the notification is a projection of that state.

## Consequences

- The persistent notification is non-negotiable UX — design it well rather than hiding it.
- Battery-exemption onboarding is a Phase 1 deliverable with real UI, not a settings footnote.
- OEM-specific battery managers (Xiaomi, Samsung, etc.) may need vendor-specific guidance later; tracked in `docs/backlog.md`.
