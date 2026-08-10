## What this changes

<!-- The behaviour that is different afterwards, in a sentence or two. -->

## Why

<!-- The problem this solves. Link the issue if there is one. For a bug fix,
     state the root cause, not just the symptom. -->

## How it was verified

<!-- Evidence, not intent. Which tests, which CI jobs, what you saw. If you
     fixed a bug, say how you confirmed the test fails without the fix. -->

- [ ] Unit tests pass (`CI` workflow)
- [ ] Device and cross-platform tests pass (`E2E` workflow)
- [ ] New behaviour has a test that fails without this change
- [ ] Verified by hand on a real phone and PC — describe below

## Checklist

- [ ] Shared contracts (`/shared`) were changed **first** if the wire format,
      state machine or tokens moved, and both platforms follow
- [ ] User-facing strings exist in **both** English and Persian
- [ ] No new network calls, telemetry, or data leaving the device
- [ ] System changes remain transactional with verified rollback
- [ ] Docs updated (`docs/`, `README.md`, `CHANGELOG.md`) where behaviour changed

## Anything reviewers should look at closely

<!-- Trade-offs you made, alternatives you rejected, parts you are unsure about.
     Say what is NOT covered as plainly as what is. -->
