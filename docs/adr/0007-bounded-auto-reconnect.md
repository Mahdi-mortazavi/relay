# ADR-0007: Bounded auto-reconnect as an internal policy, not a new state

**Status:** Accepted · **Date:** 2026-07-07

## Context

Phase 2 requires the session to survive a **brief** hotspot drop and recover
within a stated bound (AC2.3). The obvious model is to add a `Reconnecting`
state to the shared machine — but the shared machine (`/shared/connection-states.json`)
is a hard cross-platform contract, and adding a top-level state ripples through
both apps' enums, transition tables, tests, and UIs.

## Decision

Keep the canonical **five-state machine unchanged**. Model reconnect as a
**bounded internal policy** layered under the existing `Connected`/`Advertising`
states:

- A shared, pure `ReconnectPolicy` (schedule + bound) lives in
  [`/shared/reconnect.md`](../../shared/reconnect.md) and is unit-tested on both
  platforms against `/shared/test-vectors.json → reconnect`.
- During the retry window the UI stays in its current state, annotated
  "Reconnecting…"; it flips to `Error` **only** when the budget is exhausted,
  via the existing `failure` transition. No new states, no contract change.
- **Windows keeps the system proxy applied** for the whole window so a 3-second
  blip never bounces the user's proxy settings (transactional safety still
  holds: exhaustion or explicit Disconnect performs the verified rollback).
- **Android keeps the foreground service up** and re-attempts to bind the
  hotspot interface on the same schedule.

## Consequences

- The five-state contract stays stable; "Reconnecting" is a presentation
  concern, not a protocol state. Honest reporting is preserved because the
  annotation is only shown while retries are genuinely in flight, and the state
  becomes `Error` the moment the bound is exceeded.
- The recovery bound (~11 s) is a single number defined once in `/shared` and
  asserted by tests on both platforms, so AC2.3 is CI-verifiable at the policy
  level and hardware-verifiable end to end.
- If a future need for a truly distinct reconnect *protocol* state appears (e.g.
  Full Mode renegotiation), it can be added then with its own ADR; we don't
  pay that cost pre-emptively.
