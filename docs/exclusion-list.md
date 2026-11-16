# Relay Exclusion List (Leak Protection & Kill Switch)

This document defines the exact exclusions required and supported for Relay's Full Mode leak protection (`relaywg-client.exe`) and kill switch so traffic stays sealed inside the WireGuard tunnel without breaking Relay's own transport or local loopback.

---

## 1. Built-in Automatic Exclusions (Hardened in WFP)

When **Block traffic around the tunnel** (`LeakProtection`) is enabled, `relaywg-client.exe` installs a default-deny policy in its own dedicated high-priority (`0xFFFF`) WFP sublayer across all four ALE layers (`ALE_AUTH_CONNECT_V4/V6` and `ALE_AUTH_RECV_ACCEPT_V4/V6`). Only these mandatory, leak-safe allowances are permitted automatically:

| Weight | Rule | Scope / Match | Why it is safe & necessary |
|---|---|---|---|
| `15` | **Loopback** | `FWP_CONDITION_FLAG_IS_LOOPBACK` (IPv4 & IPv6, connect & recv-accept) | Prevents localhost (`127.0.0.1` / `::1`) breakage; loopback never hits a physical NIC. |
| `15` | **Relay Tunnel Binary (UDP only)** | `ALE_APP_ID == <install_dir>\relaywg-client.exe` AND `IPPROTO_UDP` | Allows encrypted WireGuard handshakes, keepalives, and roaming to the phone. TCP is **not** exempted. |
| `15` | **Pinned Tunnel DNS** | `IP_LOCAL_INTERFACE == WinTun LUID` AND `port == 53` (UDP/TCP) AND `remote == resolver` (`1.1.1.1`) | Strictly binds DNS to the WinTun adapter; queries to `1.1.1.1:53` on Wi-Fi/Ethernet are still blocked. |
| `14` | **Hard Name-Resolution Block** | Blocks UDP/TCP `53` (non-tunnel), UDP `5353` (mDNS), UDP/TCP `5355` (LLMNR), UDP `137` (NetBIOS-NS) | Sits above all user exclusions (`weight 12`) so excluded apps/subnets cannot leak DNS queries. |
| `12` | **WinTun Adapter (IPv4 only)** | `IP_LOCAL_INTERFACE == WinTun LUID` (`CONNECT_V4` & `RECV_ACCEPT_V4`) | Carries all tunnel IPv4 traffic. IPv6 remains completely blocked. |
| `12` | **DHCPv4** | UDP `68 <-> 67` (`255.255.255.255` outbound) | Keeps the underlying PC-to-phone Wi-Fi/hotspot/USB lease alive during long sessions. |

Everything else on every physical or virtual non-tunnel interface—including IPv6, non-tunnel DoH (`443`), DoT (`853`), source-bound sockets, lower-metric adapters, and specific routes—hits the weight `0` **Block All** rule.

---

## 2. Optional User Exclusion List (`Advanced` UI or `exclusions.json`)

You can configure exclusions directly from Relay's Windows **Advanced** panel (**Allow local network (LAN)**, **Allow phone discovery (roaming)**, **Keep kill switch across crashes**, **Browse…** for any `.exe`, editable **Bypassed apps**, and **Bypassed IPs / subnets (CIDR)**) — changes are validated and saved automatically to:

```text
%LOCALAPPDATA%\Relay\exclusions.json
```

(Or edit the JSON file directly; see [exclusions.example.json](exclusions.example.json) for a copyable starter file.)

### Supported Fields

| Field | Default | Description & Risk Notes |
|---|---|---|
| `allowLan` | `false` | Permits IPv4 private/link-local ranges (`10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16`, `169.254.0.0/16`) for local printers, SSH, SMB shares, or NAS devices. **Port 53/5353/5355/137 stays blocked** so your Wi-Fi router cannot intercept DNS. |
| `allowDiscovery` | `false` | Permits `Relay.App.exe` on **UDP port `47654` only** so Relay can receive pairing beacons and automatically follow your phone if the phone's LAN DHCP IP changes mid-session (`RoamAsync`). Keep `false` on untrusted public Wi-Fi. |
| `strictLeakBlock` | `true` | Fail closed: refuses to bring up the tunnel if WFP filters fail to install. Keep `true` unless debugging filter creation. |
| `persistKillSwitch` | `false` | When **Kill switch** (`-hold-on-loss`) is also on, persists WFP lockdown rules (`FWPM_FILTER_FLAG_PERSISTENT`) across app/tunnel crashes until explicit **Disconnect**, next Relay startup recovery, or `relaywg-client.exe -clear-killswitch`. |
| `excludeApps` | `[]` | Array of **fully qualified `.exe` paths** allowed to bypass the tunnel on **IPv4 non-DNS traffic** (e.g. an app incompatible with double-NAT). |
| `excludeCidrs` | `[]` | Array of specific **IPv4/IPv6 CIDR prefixes** (e.g. `"203.0.113.10/32"`, `"192.168.50.0/24"`) allowed outside the tunnel. Default routes (`0.0.0.0/0`, `::/0`) are automatically rejected. |

### CLI Recovery Command (Persistent Kill Switch)

If you enabled `persistKillSwitch: true` + Kill switch and terminated Relay externally, unlock Windows networking by either:
- Re-opening **Relay** (startup crash recovery detects `persistent-killswitch.armed` and offers elevation to clear it), **or**
- Running from an elevated Administrator terminal:

```powershell
"$env:LOCALAPPDATA\Programs\Relay\relaywg-client.exe" -clear-killswitch
```

*(Adjust path if Relay is installed in another directory.)*

---

## 3. Recommended Windows Exclusion List Reference

### Relay Application Files (Antivirus / Third-Party Firewall Exclusions)
If Microsoft Defender, Kaspersky, ESET, or another third-party firewall interferes with the WinTun adapter or blocks WFP registration:
- `%LOCALAPPDATA%\Programs\Relay\Relay.App.exe` (or your install path)
- `%LOCALAPPDATA%\Programs\Relay\relaywg-client.exe`
- `%LOCALAPPDATA%\Programs\Relay\wintun.dll`
- `%LOCALAPPDATA%\Relay\` (user settings, logs, and `exclusions.json`)

> If Relay's **Discovery / Roaming** is enabled (`allowDiscovery: true`), third-party firewalls should only allow **UDP port `47654`** for `Relay.App.exe` on Private networks.

### NEVER Add These to `excludeApps` or `excludeCidrs`
Relay automatically rejects these dangerous entries (and you must never exclude them manually in a firewall either), because doing so creates an immediate full bypass hole:
- `0.0.0.0/0` or `::/0` (disables all blocking)
- Multi-tenant system / scripting host executables:
  - `svchost.exe` (Windows DNS Client, Delivery Optimization, telemetry, WinHTTP services)
  - `cmd.exe`, `powershell.exe`, `pwsh.exe`, `conhost.exe`
  - `rundll32.exe`, `regsvr32.exe`, `mshta.exe`, `wscript.exe`, `cscript.exe`
  - `services.exe`, `lsass.exe`, `csrss.exe`, `explorer.exe`, `backgroundtaskhost.exe`
- Public DoH/DNS resolver IPs (`1.1.1.1/32`, `8.8.8.8/32`, `9.9.9.9/32`) in `excludeCidrs` — if excluded, apps can send DoH on port `443` around the tunnel directly to those resolvers.

---

## 4. Phone-Side Exclusions (Android VPN Apps)

When Relay shares an existing VPN running on your Android phone (such as v2rayNG, NekoBox, Clash / FlClash X, or WireGuard), configure split-tunnel / bypass exclusions inside that Android VPN app as well so pairing and WireGuard transport between phone and laptop are not captured into the upstream VPN:
- **App bypass (only if your phone VPN blocks local LAN return traffic):** `io.relay.app`
- **Subnet bypass (preferred when supported by the VPN app):**
  - `192.168.0.0/16` (Wi-Fi / Android Wi-Fi hotspot default subnets)
  - `172.16.0.0/12` and `10.0.0.0/8` (USB tethering `rndis0` and carrier-private hops)
  - `10.13.37.0/24` (Relay Full Mode internal WireGuard subnet — must route through Relay, **not** be hijacked back into another VPN's `tun0`)
- Do **not** disable Android's *LAN Bypass* while expecting Relay pairing replies to reach the PC; see [VPN compatibility](vpn-compat.md) for Group 1/2/3 details.
