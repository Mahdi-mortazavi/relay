# Typed-code fallback — contract (v1)

The camera-less pairing path: the phone shows a short human-typeable code alongside the QR; the Windows app accepts either. Both platforms implement this codec identically; vectors live in [`test-vectors.json`](test-vectors.json) under `typedCodes`.

## Scope (v1)

- Encodes **an address only**, and only hosts inside `192.168.0.0/16` (true for stock Android hotspots). If the hotspot IP is outside that range the phone hides the typed code and offers QR only.
- Since [ADR-0009](../docs/adr/0009-full-mode-only-and-code-pairing.md) an address is enough. The code used to be Fast Mode only, because Full Mode's key material does not fit in something a person can type and an address alone could not build a tunnel. It can now: the PC dials the pairing port at that address and the keys come over [the pairing exchange](pairing-beacon.md#the-pairing-exchange), gated by the person holding the phone.
- This matters beyond convenience. The two-digit code is withheld when the phone cannot announce itself, so a phone that also refused to show this one would display **no code at all** on a network without broadcast — shareable by QR alone, with nothing on screen explaining why.

## Algorithm

```
bytes  = [ o3, o4, port >> 8, port & 0xFF ]        // host = 192.168.o3.o4
check  = CRC8(bytes) & 0x1F                        // CRC-8, poly 0x07, init 0x00,
                                                   // no reflection, xorout 0x00
V      = b0<<32 | b1<<24 | b2<<16 | b3<<8 | check<<3   // 40-bit value, low 3 bits zero
code[i] = ALPHABET[ (V >> (35 - 5*i)) & 31 ]       // i = 0..7  → 8 characters
ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"      // base32, no O/0/I/1
```

- **Display:** grouped as `XXXX-XXXX`.
- **Input:** case-insensitive; strip whitespace and `-` before decoding.
- **Decode validation:** exactly 8 alphabet characters after stripping; the 3 padding bits MUST be zero; recomputed checksum MUST match. Any failure → error code `ERR_CODE_INVALID`.
- Decoded result is an address: `host=192.168.<o3>.<o4>`, `port`. It is not a complete pairing on its own — the client still has to fetch a configuration from the pairing port before it can dial anything.

## Versioning

The typed code carries no version field (too short); its version is tied to the QR payload version the app pair supports. A future scheme change accompanies a `v` bump of the QR payload.
