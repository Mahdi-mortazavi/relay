# Typed-code fallback — contract (v1 / v1.1 RFC1918)

The camera-less pairing path: the phone shows a short human-typeable code alongside the QR; the Windows app accepts either. Both platforms implement this codec identically; vectors live in [`test-vectors.json`](test-vectors.json) under `typedCodes`.

## Scope

- Encodes **an RFC1918 private IPv4 address + port**, in two compact lengths:
  - **8 characters (`XXXX-XXXX`, 40 bits):** hosts in `192.168.0.0/16` (legacy compatible format; every existing 8-character code remains valid).
  - **10 characters (`XXXXX-XXXXX`, 50 bits):** hosts in `10.0.0.0/8` and `172.16.0.0/12` (so carrier/USB/Wi-Fi/workstation subnets outside `192.168/16` also get a fallback code without QR).
- Hosts outside RFC1918 (`10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16`) return `null` on encode.
- Since [ADR-0009](../docs/adr/0009-full-mode-only-and-code-pairing.md) an address is enough: the PC dials the pairing port at that address and the keys come over [the pairing exchange](pairing-beacon.md#the-pairing-exchange), gated by the person holding the phone.

## Algorithm

Common parameters:
- `ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"` (base32 without `O/0/I/1`)
- `CRC8(data)` = CRC-8/SMBUS over `data` (poly `0x07`, init `0x00`, no reflection, xorout `0x00`)

### 8-character format (`192.168.o3.o4`)
```
bytes  = [ o3, o4, port >> 8, port & 0xFF ]        // host = 192.168.o3.o4
check  = CRC8(bytes) & 0x1F
V      = b0<<32 | b1<<24 | b2<<16 | b3<<8 | check<<3   // 40-bit value, low 3 bits zero
code[i] = ALPHABET[ (V >> (35 - 5*i)) & 31 ]       // i = 0..7  → 8 characters
```
- **Display:** grouped as `XXXX-XXXX`.
- **Decode validation:** exactly 8 alphabet characters after stripping whitespace/`-`; the 3 padding bits MUST be zero; recomputed checksum MUST match.

### 10-character format (`10.0.0.0/8` and `172.16.0.0/12`)
```
tag = 1 for 10.h0.h1.h2     (o1 == 10)
    = 2 for 172.(16+h0).h1.h2  (o1 == 172 && o2 in 16..31, so h0 = o2 - 16 in 0..15)
bytes  = [ tag, h0, h1, h2, port >> 8, port & 0xFF ]
check  = CRC8(bytes)                               // full 8-bit CRC-8
V      = (tag << 48) | (h0 << 40) | (h1 << 32) | (h2 << 24) | (port << 8) | check   // 50-bit value
code[i] = ALPHABET[ (V >> (45 - 5*i)) & 31 ]       // i = 0..9  → 10 characters
```
- **Display:** grouped as `XXXXX-XXXXX`.
- **Decode validation:** exactly 10 alphabet characters after stripping whitespace/`-`; `tag` MUST be `1` (`10/8`) or `2` (`172.16/12` with `h0 <= 15`); `port` in `1..65535`; recomputed 8-bit `CRC8(bytes)` MUST match.

- **Input:** case-insensitive; strip whitespace and `-` before decoding. Any failure → error code `ERR_CODE_INVALID`.

## Versioning

Both lengths are decoded unambiguously by character count (8 vs 10) and validated strictly by zero padding bits (8-char) or tag/range + full 8-bit CRC (10-char).
