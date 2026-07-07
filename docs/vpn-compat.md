# VPN compatibility

How Relay composes with the VPN already running on the Android phone.

- **Fast Mode (SOCKS5):** the proxy opens ordinary sockets on the phone; Android routes them through the active system VPN like any app traffic. No interaction with `VpnService` at all.
- **Full Mode (WireGuard):** uses `VpnService`-adjacent machinery; interactions with the user's existing VPN app are non-trivial and will be documented here.

> The compatibility matrix below is populated in Phase 2 by testing installable CI artifacts on real hardware against at least three popular Android VPN apps.

| VPN app | Fast Mode | Full Mode | Notes |
|---|---|---|---|
| _(tested in Phase 2)_ | — | — | — |
