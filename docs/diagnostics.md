# Reading a diagnostic report

Relay's log never leaves the phone unless its owner sends it. When one arrives,
this is how to read it — and, for anyone adding a log line, what the format is
promising.

## The line

```
    0.00  INFO  service Starting sharing
    0.01  INFO  link    Reply path checked before any PC asked  probe=192.168.1.1 advertised=192.168.1.5 verdict=leaves-by-the-link
    8.01  ERROR link    No usable Wi-Fi, hotspot or USB link  waited_ms=8000 looks=8 vpn=true links="rndis0(no-ipv4) wlan0(192.168.1.5 wifi score-3) tun0(10.8.0.2 vpn)"
```

| Column | Meaning |
|---|---|
| time | Seconds **since the app's process started** — not since sharing started, whatever an older build's header claimed |
| level | `INFO` · `WARN` · `ERROR` |
| area | `service` · `link` · `pairing` · `tunnel` · `update` |
| message | A sentence, with no values in it |
| fields | `key=value`, quoted when the value contains a space |

The last line of the report counts the errors and warnings, because a report is
usually read by somebody scrolling a chat on a phone.

## Why values live in fields

A value in a sentence has to be reworded every time it appears, so it cannot be
searched for. `advertised=192.168.1.5` is the same string in every line that
mentions it; "the address we advertised was 192.168.1.5" is not.

## The `links` field

The single most useful thing in a report about a connection that will not start.
It names **every** interface, including the ones Relay deliberately refuses, with
why:

| Verdict | Meaning | What to tell the user |
|---|---|---|
| `no-ipv4` | Up, no address yet | **Wait and retry** — USB tethering looks exactly like this for a few seconds after it is switched on |
| `down` | Present, not up | Plug the cable in / turn the hotspot on |
| `cellular` | The carrier's own link | No PC can route to it; join a Wi-Fi or turn the hotspot on |
| `vpn` | A tun interface | Ignored by design; a PC cannot reach the phone through it |
| `wifi` `hotspot` `usb` | Advertisable, with its `score-N` | The highest score is the one in the QR |

This exists because a report once said `No usable Wi-Fi or hotspot interface
found` seven times, and answering it took a message to the user in another
country. Their cable was plugged in with tethering just switched on — the
`no-ipv4` case — which one line would have said.

## Two lines that look like each other

`Reply path checked …` appears twice: once at start-up against a stand-in
address, once for the real PC once it asks. Both are **informational only**.

The probe asks the kernel which interface a datagram *would* leave by. It cannot
ask whether the packet arrives, and it has been observed saying
`verdict=would-leave-by-vpn` on a session that paired a PC and carried traffic
sixty seconds later. Triage with it; never conclude from it. What *does* mean the
replies are lost is the `PC_GOT_NO_REPLY` warning — see
[`vpn-compat.md`](vpn-compat.md).

## Adding a line

- Pick the **level** by what a reader should do: `ERROR` is "this is why it did
  not work", `WARN` is "working, but not the way it should be", `INFO` is the
  rest.
- Put every value in a **field**, not in the sentence.
- Where a failure has context worth capturing, capture it **at the moment of the
  failure** — `LinkSnapshot.take()` costs one enumeration and is the difference
  between a report that can be answered and one that needs a conversation.
- Do not log anything that is not already on the device. Nothing here is
  uploaded, and the report says so; keep that true.
