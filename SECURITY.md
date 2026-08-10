# Security

Relay moves your network traffic and changes your operating system's proxy
settings. Both are things you are entitled to be careful about, so this document
says plainly what Relay does, what it deliberately does not do, and where the
sharp edges are.

## Reporting a vulnerability

Please report privately, **not** as a public issue:

- Use [GitHub's private vulnerability reporting](https://github.com/Mahdi-mortazavi/relay/security/advisories/new)
  on this repository.

Please include what you did, what happened, and what you expected. A proof of
concept helps enormously. You will get a first response within **7 days**, and
an assessment with a fix plan or a reasoned decline within **30 days**. If a fix
ships, you are credited in the release notes unless you would rather not be.

This is a small volunteer project with no bug bounty. Reports are still very
welcome.

## What Relay guarantees

**Local-only, zero telemetry.** The apps open no network connections other than
relaying your own traffic between your two devices. No analytics, no accounts,
no crash reporting, no update pings, no cloud service. The diagnostic log in
Advanced settings is an in-memory ring buffer that is never written to disk and
never leaves the device.

**Transactional system changes.** Before Relay touches the Windows proxy
settings it snapshots them to `%LOCALAPPDATA%\Relay\proxy-backup.json`, writes
its own values, and reads them back to verify. On disconnect it restores the
snapshot and verifies that too; a failed restore is surfaced as
`ERR_ROLLBACK_INCOMPLETE` rather than silently ignored. If the app dies without
disconnecting, the next launch restores the snapshot — and while a session is
applied, Relay registers a one-shot recovery entry so a sign-out, shutdown or
force-kill is repaired at next sign-in instead of leaving you with a dead proxy.

**Per-pairing key material.** Full Mode's WireGuard keys are generated fresh for
each pairing and discarded on disconnect. (Full Mode is not implemented yet —
see below.)

## Threat model, stated honestly

Relay's transport is a **SOCKS5 proxy with no authentication**, bound to all
interfaces on the phone. That is a deliberate constraint, not an oversight:
Windows configures a system-wide SOCKS proxy through WinINet, which has no way
to supply credentials. Requiring authentication would break the "no manual
network configuration" property that is the entire point of the product.

The practical consequences:

| Scenario | Exposure |
|---|---|
| Phone's own hotspot, only your laptop joined | The intended case. Only your own devices are on the network. |
| Phone's hotspot with other devices joined | Anyone on that hotspot can use the proxy. You control who has the hotspot password. |
| **Shared Wi-Fi (café, office, hotel)** | **Anyone on that network who finds the port can relay traffic through your phone**, consuming your data and appearing to originate from your connection. |

The QR payload is not a secret — it carries an address and a port, both of which
are discoverable by scanning the network. Treat "start sharing" as "open a proxy
on this network", and prefer your own hotspot when you are not on a network you
trust.

Closing this properly needs a pairing handshake that does not rely on the SOCKS
layer for authentication — for example, admitting only the first client that
completes an explicit confirmation on the phone, and asking about any device
after it. The `PairingStrategy` seam exists for exactly this, and it is tracked
in [`docs/backlog.md`](docs/backlog.md). Until then, the table above is the
accurate description of what you are running.

### Also worth knowing

- **The phone resolves DNS for the PC.** That is the point — it keeps DNS inside
  whatever VPN is active on the phone — but it does mean the phone sees every
  hostname the PC looks up.
- **Relay does not encrypt anything itself.** In Fast Mode it relays TCP
  verbatim; your traffic's confidentiality is whatever TLS and the phone's VPN
  already give it. Relay adds no protection to plaintext traffic.
- **The Windows installers are unsigned.** SmartScreen will warn on first run.
  Code signing is tracked in [`docs/release.md`](docs/release.md); until then,
  verify the checksums published with each release.
- **Windows changes are per-user (`HKCU`)** and the installer never asks for
  administrator rights.

## Supported versions

Relay is pre-1.0. Only the latest release receives fixes. There are no
long-term-support branches.

## Automated checks

Every pull request runs static analysis and dependency review
([`.github/workflows/security.yml`](.github/workflows/security.yml)), alongside
the unit, device and cross-platform test suites described in
[`docs/testing.md`](docs/testing.md).
