# Architecture Decision Records

Short, immutable records of significant decisions. New decision → new numbered file; superseding → new ADR that references the old one (never edit history).

| ADR | Title | Status |
|---|---|---|
| [0001](0001-two-transport-modes.md) | Two transport modes — SOCKS5 (Fast) and WireGuard (Full) | Accepted |
| [0002](0002-no-discovery-qr-pairing.md) | No local discovery — direct connect via versioned QR payload | Accepted |
| [0003](0003-foreground-service-strategy.md) | Foreground service + battery-exemption onboarding on Android | Accepted |
| [0004](0004-github-only-build-and-release.md) | GitHub Actions is the only build, test, and release system | Accepted |
| [0005](0005-windows-stack-winui3.md) | Windows stack — .NET 8 + WinUI 3, unpackaged, EXE installer | Accepted |
| [0006](0006-in-repo-socks5-server.md) | In-repo minimal SOCKS5 server on Android | Accepted |
| [0007](0007-bounded-auto-reconnect.md) | Bounded auto-reconnect as an internal policy, not a new state | Accepted |
