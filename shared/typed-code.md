# Typed-code fallback — contract (v1)

The camera-less pairing path: the phone shows a short human-typeable code alongside the QR; the Windows app accepts either. Both platforms implement this codec identically; vectors live in [`test-vectors.json`](test-vectors.json) under `typedCodes`.

## Scope (v1)

- Encodes **Fast Mode (socks5) only**, and only hosts inside `192.168.0.0/16` (true for stock Android hotspots). If the hotspot IP is outside that range the phone hides the typed code and offers QR only.
- Full Mode cannot be typed (key material doesn't fit a human code); QR only.

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
- Decoded result is a v1 payload: `mode=socks5`, `host=192.168.<o3>.<o4>`, `port`.

## Versioning

The typed code carries no version field (too short); its version is tied to the QR payload version the app pair supports. A future scheme change accompanies a `v` bump of the QR payload.
