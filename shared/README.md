# /shared — single source of truth

Contracts both apps consume. **Never** duplicate these values into platform code without referencing this directory as the origin; when a contract changes, it changes here first and both platforms follow in the same PR.

| File | What it pins | Consumed by |
|---|---|---|
| [`qr-payload.schema.json`](qr-payload.schema.json) | Versioned QR pairing payload (JSON Schema draft-07) | Both apps' payload validators |
| [`test-vectors.json`](test-vectors.json) | Valid/invalid payloads + encoded forms | Both apps' unit tests (round-trip + rejection) |
| [`connection-states.json`](connection-states.json) | Connection state machine: states, initial state, transitions | Both apps' state-machine tests |
| [`design-tokens.json`](design-tokens.json) | Liquid Glass tokens: colors, radii, blur, spacing, motion, type | Both apps' theme layers |

## Encoding contract (QR + typed code)

- **QR:** compact JSON → UTF-8 → base64url **without padding** → QR code.
- **Round-trip tests** assert semantic equality of decoded payloads (key order and whitespace are not significant).
- **Typed-code fallback** (Phase 1): a short base32 code (6–8 chars, alphabet excludes `O/0/I/1`) that the phone maps to the same payload. Defined in detail when implemented.

## Versioning

`v` is the schema version. Clients MUST reject payloads with an unknown `v` and show the "made by a newer version of Relay" message. New fields within `v:1` are not allowed (`additionalProperties: false`) — any wire change bumps `v`.
