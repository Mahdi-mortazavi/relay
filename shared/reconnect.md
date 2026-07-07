# Auto-reconnect policy — contract (v1)

A **brief** hotspot drop (the AP interface flaps, the laptop's Wi-Fi blips) must
not tear the session down. Both apps run the same bounded retry schedule before
giving up; the schedule is pure data so each platform unit-tests it and neither
drifts. Backing decision: [ADR-0007](../docs/adr/0007-bounded-auto-reconnect.md).

## Schedule

```
attemptDelaysMs = [1000, 2000, 2000, 3000, 3000]   // 5 attempts
```

- Attempt *i* is made after waiting `attemptDelaysMs[i]` from the previous
  failure (the first retry waits 1 s after the drop is detected).
- **Stated bound:** the whole window is `sum(attemptDelaysMs) = 11 000 ms`. If
  connectivity has not returned by then, the session surfaces an error via the
  existing `failure` transition (→ `Error`). So: **recovery from a brief drop is
  automatic and completes within ≈ 11 s; anything longer becomes a normal,
  actionable error** (`ERR_HOTSPOT_LOST` on the phone, `ERR_CONNECTION_LOST` on
  the PC).
- A successful attempt resets the schedule: a later, separate drop gets the full
  budget again.

## Behaviour during the retry window

- The UI stays in its connected/advertising state, annotated "Reconnecting…";
  it does **not** flip to `Error` until the budget is exhausted (no flapping).
- **Windows:** the system proxy stays applied for the whole window — a 3-second
  blip must not bounce the user's proxy settings. Only exhaustion (or an
  explicit Disconnect) rolls it back.
- **Android:** the foreground service and notification stay up; the server keeps
  attempting to (re)bind the hotspot interface.

## Test vectors

`test-vectors.json → reconnect` carries `attemptDelaysMs`, `attempts` (5), and
`totalBoundMs` (11000). Each platform asserts its `ReconnectPolicy` equals these.
