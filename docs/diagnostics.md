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

The report's header carries `Lockdown: on / off / unknown` — Android's *"Block
connections without VPN"*. `unknown` means the platform would not answer, **not**
that it is off; see [`vpn-compat.md`](vpn-compat.md).

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

## Checking whether anything crosses the tunnel

**Not with `ping`.** Relay forwards TCP and UDP and nothing else — there is no
unprivileged way for an Android app to send ICMP, and a SOCKS5 upstream
(`upstream.go`) cannot carry it at all. So a ping to a public address is not a
test of anything, and until 2.8.9 it was worse than that: the gVisor stack has
to be promiscuous for the forwarders to see packets addressed to the internet,
and that made it answer echo requests for **every address in the world** from
inside the phone. `ping 1.1.1.1` could not fail.

That cost a real user an evening. Their report read:

```
ping 1.1.1.1      ->  Reply ... time=49ms TTL=64
tracert 1.1.1.1   ->  1   315 ms  291 ms  55 ms  one.one.one.one [1.1.1.1]
nslookup google.com -> DNS request timed out
```

**`TTL=64` and a one-hop traceroute are the tell.** A real 1.1.1.1 is dozens of
hops away and answers with a TTL in the forties; a responder that is one hop
away and ignores TTL is a local stack. Since 2.8.9 those requests are dropped, so
a ping that fails means "Relay does not carry ping", not "the internet is down".

Ask the two questions separately instead, from the PC while connected:

| Question | Command | A working tunnel |
|---|---|---|
| Is the tunnel itself alive? | `ping 10.13.37.1` | replies — this is Relay's own end, and the only address it answers for |
| Does **TCP** cross? | `curl.exe -sS --max-time 15 -o NUL -w "%{http_code}\n" https://1.1.1.1/` | `200` |
| Does **UDP** cross? | `nslookup google.com 8.8.8.8` | an address |
| Whose internet is it? | `curl.exe -sS --max-time 20 https://www.cloudflare.com/cdn-cgi/trace` | `ip=` the phone's exit, and `warp=on` if the phone's VPN is being shared |

Read them in that order and stop at the first failure — TCP working while UDP
does not is a different fault from neither working, and the phone's VPN is the
usual reason for the first. Read `ip=` and the laptop's own `ip=` from the
**same** service or the comparison means nothing; two "what is my IP" endpoints
do not have to agree, and one that disagreed cost a published claim a correction.

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
