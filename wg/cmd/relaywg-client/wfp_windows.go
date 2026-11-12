//go:build amd64 || arm64 || 386 || arm

package main

// Blanket Windows Filtering Platform (WFP) leak protection and kill switch.
//
// Relay runs as an elevated user process (ADR-0005), not a SYSTEM service, so
// wireguard-windows' firewall package fails with ERROR_NO_SUCH_GROUP inside
// permitWireGuardService. Previously, Relay compensated by blocking only IPv6
// and non-tunnel port 53 inside FWPM_SUBLAYER_UNIVERSAL at weight 8. That left
// all non-53 IPv4 traffic outside the tunnel completely unblocked (allowing
// source-IP binding, specific routes, DoH/DoT, mDNS/LLMNR/NBNS, lower-metric
// adapters, and unpinned 1.1.1.1:53 queries on Wi-Fi/Ethernet to leak).
//
// This implementation registers Relay's own high-priority sublayer (0xFFFF) and
// provider, and installs a strict default-deny policy across all four ALE
// layers (connect/recv-accept for IPv4 and IPv6), permitting only:
//   - loopback (IPv4 and IPv6, so localhost never stalls)
//   - relaywg-client.exe UDP traffic (WireGuard encrypted transport & roaming)
//   - DNS (UDP/TCP 53) strictly pinned to the WinTun LUID + tunnel resolvers
//   - IPv4 traffic bound for the WinTun LUID
//   - IPv4 DHCP (UDP 68<->67) so the physical link lease stays valid
//   - explicit user exclusions (LAN, discovery port 47654, apps, CIDRs)
//
// Name-resolution protocols (DNS 53, mDNS 5353, LLMNR 5355, NetBIOS-NS 137)
// remain blocked outside the WinTun DNS pin at weight 14 — higher than any
// app, LAN, or CIDR exclusion — so excluded ranges cannot reintroduce DNS leaks.

import (
	"encoding/binary"
	"errors"
	"fmt"
	"net/netip"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"unsafe"

	"golang.org/x/sys/windows"
)

type fwpmDisplayData0 struct {
	name        *uint16
	description *uint16
}

type fwpByteBlob struct {
	size uint32
	data *uint8
}

type fwpValue0 struct {
	kind  uint
	value uintptr
}

type fwpConditionValue0 struct {
	kind  uint
	value uintptr
}

type fwpmFilterCondition0 struct {
	fieldKey       windows.GUID
	matchType      uint
	conditionValue fwpConditionValue0
}

type fwpmAction0 struct {
	kind       uint32
	filterType windows.GUID
}

type fwpmSession0 struct {
	sessionKey           windows.GUID
	displayData          fwpmDisplayData0
	flags                uint32
	txnWaitTimeoutInMSec uint32
	processId            uint32
	sid                  *windows.SID
	username             *uint16
	kernelMode           uint8
}

type fwpmProvider0 struct {
	providerKey  windows.GUID
	displayData  fwpmDisplayData0
	flags        uint32
	providerData fwpByteBlob
	serviceName  *uint16
}

type fwpmSublayer0 struct {
	subLayerKey  windows.GUID
	displayData  fwpmDisplayData0
	flags        uint32
	providerKey  *windows.GUID
	providerData fwpByteBlob
	weight       uint16
}

type fwpV4AddrAndMask struct {
	addr uint32
	mask uint32
}

type fwpV6AddrAndMask struct {
	addr         [16]uint8
	prefixLength uint8
}

// Compile-time verification for all shared kernel structures across 32/64-bit.
const (
	_ = uint(unsafe.Sizeof(fwpByteBlob{}) - wtFwpByteBlobSize)
	_ = uint(wtFwpByteBlobSize - unsafe.Sizeof(fwpByteBlob{}))
	_ = uint(unsafe.Sizeof(fwpmDisplayData0{}) - wtFwpmDisplayData0Size)
	_ = uint(wtFwpmDisplayData0Size - unsafe.Sizeof(fwpmDisplayData0{}))
	_ = uint(unsafe.Sizeof(fwpValue0{}) - wtFwpValue0Size)
	_ = uint(wtFwpValue0Size - unsafe.Sizeof(fwpValue0{}))
	_ = uint(unsafe.Sizeof(fwpmFilterCondition0{}) - wtFwpmFilterCondition0Sz)
	_ = uint(wtFwpmFilterCondition0Sz - unsafe.Sizeof(fwpmFilterCondition0{}))
	_ = uint(unsafe.Sizeof(fwpmSession0{}) - wtFwpmSession0Size)
	_ = uint(wtFwpmSession0Size - unsafe.Sizeof(fwpmSession0{}))
	_ = uint(unsafe.Sizeof(fwpmProvider0{}) - wtFwpmProvider0Size)
	_ = uint(wtFwpmProvider0Size - unsafe.Sizeof(fwpmProvider0{}))
	_ = uint(unsafe.Sizeof(fwpmSublayer0{}) - wtFwpmSublayer0Size)
	_ = uint(wtFwpmSublayer0Size - unsafe.Sizeof(fwpmSublayer0{}))
	_ = uint(unsafe.Sizeof(fwpmAction0{}) - 20)
	_ = uint(20 - unsafe.Sizeof(fwpmAction0{}))
	_ = uint(unsafe.Sizeof(fwpV4AddrAndMask{}) - 8)
	_ = uint(8 - unsafe.Sizeof(fwpV4AddrAndMask{}))
	_ = uint(unsafe.Sizeof(fwpV6AddrAndMask{}) - 17)
	_ = uint(17 - unsafe.Sizeof(fwpV6AddrAndMask{}))
)

const (
	fwpActionFlagTerminating = 0x00001000
	fwpActionBlock           = 0x00000001 | fwpActionFlagTerminating
	fwpActionPermit          = 0x00000002 | fwpActionFlagTerminating

	fwpMatchEqual       = 0
	fwpMatchFlagsAllSet = 6

	fwpUint8        = 1
	fwpUint16       = 2
	fwpUint32       = 3
	fwpUint64       = 4
	fwpByteBlobType = 12
	fwpV4AddrMask   = 256
	fwpV6AddrMask   = 257

	fwpConditionFlagIsLoopback = 0x00000001

	fwpmSessionFlagDynamic     = 0x00000001
	fwpmProviderFlagPersistent = 0x00000001
	fwpmSublayerFlagPersistent = 0x00000001

	fwpmFilterFlagPersistent       = 0x00000001
	fwpmFilterFlagClearActionRight = 0x00000008

	rpcCAuthnWinNT = 10

	ipProtoTCP = 6
	ipProtoUDP = 17

	dnsPort       = 53
	netbiosPort   = 137
	mdnsPort      = 5353
	llmnrPort     = 5355
	discoveryPort = 47654
)

var (
	// Deterministic GUIDs used when persistent kill-switch lockdown is enabled,
	// so crash recovery and -clear-killswitch can locate and remove them.
	relayProviderKey = windows.GUID{
		Data1: 0x6b2f8d41, Data2: 0x3a91, Data3: 0x4e7c,
		Data4: [8]byte{0x91, 0x24, 0x8f, 0x3c, 0x2a, 0x7b, 0x51, 0x09},
	}
	relaySublayerKey = windows.GUID{
		Data1: 0x9c4e1a22, Data2: 0x7d08, Data3: 0x4b15,
		Data4: [8]byte{0xa3, 0x67, 0x19, 0xe4, 0x50, 0x8c, 0x2d, 0x11},
	}

	layerAleAuthConnectV4 = windows.GUID{
		Data1: 0xc38d57d1, Data2: 0x05a7, Data3: 0x4c33,
		Data4: [8]byte{0x90, 0x4f, 0x7f, 0xbc, 0xee, 0xe6, 0x0e, 0x82},
	}
	layerAleAuthRecvAcceptV4 = windows.GUID{
		Data1: 0xe1cd9fe7, Data2: 0xf4b5, Data3: 0x4273,
		Data4: [8]byte{0x96, 0xc0, 0x59, 0x2e, 0x48, 0x7b, 0x86, 0x50},
	}
	layerAleAuthConnectV6 = windows.GUID{
		Data1: 0x4a72393b, Data2: 0x319f, Data3: 0x44bc,
		Data4: [8]byte{0x84, 0xc3, 0xba, 0x54, 0xdc, 0xb3, 0xb6, 0xb4},
	}
	layerAleAuthRecvAcceptV6 = windows.GUID{
		Data1: 0xa3b42c97, Data2: 0x9f04, Data3: 0x4672,
		Data4: [8]byte{0xb8, 0x7e, 0xce, 0xe9, 0xc4, 0x83, 0x25, 0x7f},
	}

	conditionIPLocalInterface = windows.GUID{
		Data1: 0x4cd62a49, Data2: 0x59c3, Data3: 0x4969,
		Data4: [8]byte{0xb7, 0xf3, 0xbd, 0xa5, 0xd3, 0x28, 0x90, 0xa4},
	}
	conditionIPRemoteAddress = windows.GUID{
		Data1: 0xb235ae9a, Data2: 0x1d64, Data3: 0x49b8,
		Data4: [8]byte{0xa4, 0x4c, 0x5f, 0xf3, 0xd9, 0x09, 0x50, 0x45},
	}
	conditionIPProtocol = windows.GUID{
		Data1: 0x3971ef2b, Data2: 0x623e, Data3: 0x4f9a,
		Data4: [8]byte{0x8c, 0xb1, 0x6e, 0x79, 0xb8, 0x06, 0xb9, 0xa7},
	}
	conditionIPLocalPort = windows.GUID{
		Data1: 0x0c1ba1af, Data2: 0x5765, Data3: 0x453f,
		Data4: [8]byte{0xaf, 0x22, 0xa8, 0xf7, 0x91, 0xac, 0x77, 0x5b},
	}
	conditionIPRemotePort = windows.GUID{
		Data1: 0xc35a604d, Data2: 0xd22b, Data3: 0x4e1a,
		Data4: [8]byte{0x91, 0xb4, 0x68, 0xf6, 0x74, 0xee, 0x67, 0x4b},
	}
	conditionAleAppID = windows.GUID{
		Data1: 0xd78e1e87, Data2: 0x8644, Data3: 0x4ea5,
		Data4: [8]byte{0x94, 0x37, 0xd8, 0x09, 0xec, 0xef, 0xc9, 0x71},
	}
	conditionFlags = windows.GUID{
		Data1: 0x632ce23b, Data2: 0x5167, Data3: 0x435c,
		Data4: [8]byte{0x86, 0xd7, 0xe9, 0x03, 0x68, 0x4a, 0xa8, 0x0c},
	}
)

var (
	fwpuclnt                        = windows.NewLazySystemDLL("fwpuclnt.dll")
	procFwpmEngineOpen0             = fwpuclnt.NewProc("FwpmEngineOpen0")
	procFwpmEngineClose0            = fwpuclnt.NewProc("FwpmEngineClose0")
	procFwpmTransactionBegin0       = fwpuclnt.NewProc("FwpmTransactionBegin0")
	procFwpmTransactionCommit0      = fwpuclnt.NewProc("FwpmTransactionCommit0")
	procFwpmTransactionAbort0       = fwpuclnt.NewProc("FwpmTransactionAbort0")
	procFwpmProviderAdd0            = fwpuclnt.NewProc("FwpmProviderAdd0")
	procFwpmProviderDeleteByKey0    = fwpuclnt.NewProc("FwpmProviderDeleteByKey0")
	procFwpmSubLayerAdd0            = fwpuclnt.NewProc("FwpmSubLayerAdd0")
	procFwpmSubLayerDeleteByKey0    = fwpuclnt.NewProc("FwpmSubLayerDeleteByKey0")
	procFwpmFilterAdd0              = fwpuclnt.NewProc("FwpmFilterAdd0")
	procFwpmFilterDeleteByKey0      = fwpuclnt.NewProc("FwpmFilterDeleteByKey0")
	procFwpmFilterCreateEnumHandle0 = fwpuclnt.NewProc("FwpmFilterCreateEnumHandle0")
	procFwpmFilterEnum0             = fwpuclnt.NewProc("FwpmFilterEnum0")
	procFwpmFilterDestroyEnum0      = fwpuclnt.NewProc("FwpmFilterDestroyEnumHandle0")
	procFwpmGetAppIdFromFileName0   = fwpuclnt.NewProc("FwpmGetAppIdFromFileName0")
	procFwpmFreeMemory0             = fwpuclnt.NewProc("FwpmFreeMemory0")
)

type leakOptions struct {
	tunLUID         uint64
	resolvers       []netip.Addr
	ipv6Tunnel      bool
	allowLAN        bool
	allowDiscovery  bool
	discoveryApp    string
	excludeApps     []string
	excludePrefixes []netip.Prefix
	persistent      bool
}

var lanPrefixes = []netip.Prefix{
	netip.MustParsePrefix("10.0.0.0/8"),
	netip.MustParsePrefix("172.16.0.0/12"),
	netip.MustParsePrefix("192.168.0.0/16"),
	netip.MustParsePrefix("169.254.0.0/16"),
}

// dangerousAppNames lists multi-tenant system host / scripting executables that
// must never appear in an app exclusion list because excluding them effectively
// exposes an unrestricted bypass vector for arbitrary commands.
var dangerousAppNames = map[string]struct{}{
	"svchost.exe":            {},
	"cmd.exe":                {},
	"powershell.exe":         {},
	"pwsh.exe":               {},
	"conhost.exe":            {},
	"rundll32.exe":           {},
	"regsvr32.exe":           {},
	"mshta.exe":              {},
	"wscript.exe":            {},
	"cscript.exe":            {},
	"services.exe":           {},
	"lsass.exe":              {},
	"csrss.exe":              {},
	"explorer.exe":           {},
	"backgroundtaskhost.exe": {},
}

func parsePrefixExclusions(list string) ([]netip.Prefix, error) {
	if strings.TrimSpace(list) == "" {
		return nil, nil
	}
	var out []netip.Prefix
	for _, raw := range strings.Split(list, ",") {
		item := strings.TrimSpace(raw)
		if item == "" {
			continue
		}
		p, err := netip.ParsePrefix(item)
		if err != nil {
			return nil, fmt.Errorf("exclude-cidrs %q: %w", item, err)
		}
		if p.Bits() == 0 {
			return nil, fmt.Errorf("exclude-cidrs %q: default /0 prefix is refused because it disables leak protection", item)
		}
		out = append(out, p.Masked())
	}
	return out, nil
}

func parseAppExclusions(list string) ([]string, error) {
	if strings.TrimSpace(list) == "" {
		return nil, nil
	}
	var out []string
	seen := make(map[string]struct{})
	for _, raw := range strings.Split(list, ",") {
		item := strings.TrimSpace(strings.Trim(raw, `"`))
		if item == "" {
			continue
		}
		if !filepath.IsAbs(item) {
			return nil, fmt.Errorf("exclude-apps %q: executable path must be absolute", item)
		}
		cleaned := filepath.Clean(item)
		if !strings.EqualFold(filepath.Ext(cleaned), ".exe") {
			return nil, fmt.Errorf("exclude-apps %q: only .exe binaries may be excluded", item)
		}
		base := strings.ToLower(filepath.Base(cleaned))
		if _, blocked := dangerousAppNames[base]; blocked {
			return nil, fmt.Errorf("exclude-apps %q: system host binary %s cannot be excluded safely", item, base)
		}
		key := strings.ToLower(cleaned)
		if _, dup := seen[key]; dup {
			continue
		}
		seen[key] = struct{}{}
		out = append(out, cleaned)
	}
	return out, nil
}

// enableLeakProtection installs atomic, high-weight WFP lockdown filters inside
// Relay's own 0xFFFF-weight sublayer, and returns a shutdown function that tears
// down the installed filters.
func enableLeakProtection(opts leakOptions) (func(), error) {
	if opts.tunLUID == 0 {
		return nil, errors.New("tunnel LUID is zero")
	}

	// Best-effort clear of any leftover persistent lockdown from an earlier
	// crash before applying fresh rules.
	if !opts.persistent {
		_ = clearPersistentKillSwitch()
	}

	var sessionFlags uint32
	desc := "Relay leak protection (dynamic)"
	if opts.persistent {
		sessionFlags = 0
		desc = "Relay persistent kill-switch lockdown"
	} else {
		sessionFlags = fwpmSessionFlagDynamic
	}

	engine, err := openWfpEngine("Relay", desc, sessionFlags)
	if err != nil {
		return nil, err
	}

	var providerKey, sublayerKey windows.GUID
	if opts.persistent {
		providerKey = relayProviderKey
		sublayerKey = relaySublayerKey
	} else {
		providerKey, err = windows.GenerateGUID()
		if err != nil {
			procFwpmEngineClose0.Call(engine)
			return nil, fmt.Errorf("generating WFP provider GUID: %w", err)
		}
		sublayerKey, err = windows.GenerateGUID()
		if err != nil {
			procFwpmEngineClose0.Call(engine)
			return nil, fmt.Errorf("generating WFP sublayer GUID: %w", err)
		}
	}

	var filterFlags uint32
	var objFlags uint32
	if opts.persistent {
		filterFlags = fwpmFilterFlagPersistent
		objFlags = fwpmProviderFlagPersistent
	}

	// Pin pointers & native blobs so Go GC keeps everything alive until commit.
	var pins []any
	var appIDBlobs []*fwpByteBlob
	freeAppIDs := func() {
		for _, blob := range appIDBlobs {
			if blob != nil {
				b := blob
				procFwpmFreeMemory0.Call(uintptr(unsafe.Pointer(&b)))
			}
		}
		appIDBlobs = nil
	}
	defer freeAppIDs()

	if err := runWfpTransaction(engine, func() error {
		if opts.persistent {
			if err := deletePersistentObjectsInTx(engine); err != nil {
				return err
			}
		}

		if err := registerProviderAndSublayer(engine, providerKey, sublayerKey, objFlags, &pins); err != nil {
			return err
		}

		ctx := wfpRuleContext{
			engine:      engine,
			providerKey: &providerKey,
			sublayerKey: sublayerKey,
			baseFlags:   filterFlags,
			pins:        &pins,
			appIDBlobs:  &appIDBlobs,
		}

		const (
			weightMax     uint8 = 15
			weightBlockNS uint8 = 14
			weightPermit  uint8 = 12
			weightBlock   uint8 = 0
		)

		// 1. Loopback (v4 + v6, connect + recv-accept).
		if err := ctx.permitLoopback(weightMax); err != nil {
			return err
		}

		// 2. Relay WireGuard binary (UDP only, clears lower action overrides) so
		//    encrypted WireGuard handshakes, keepalives, and roaming always reach
		//    the phone over the physical NIC without exposing TCP.
		if err := ctx.permitSelfWireGuardUDP(weightMax); err != nil {
			return err
		}

		// 3. Tunnel DNS pinned to WinTun LUID (UDP & TCP 53 to configured resolvers).
		if err := ctx.permitTunnelDNS(weightMax, opts.tunLUID, opts.resolvers); err != nil {
			return err
		}

		// 4. Block all other DNS (53) and OS name broadcasts (mDNS 5353, LLMNR 5355, NBNS 137)
		//    higher than exclusions so excluded apps/subnets cannot leak DNS.
		if err := ctx.blockNameResolution(weightBlockNS); err != nil {
			return err
		}

		// 5. Permit IPv4 traffic inside WinTun interface. Permit WinTun IPv6
		//    traffic ONLY when dual-stack IPv6 tunnel is enabled; off-tunnel
		//    IPv6 remains strictly blocked in blockAll below.
		if err := ctx.permitInterfaceIPv4(weightPermit, opts.tunLUID); err != nil {
			return err
		}
		if opts.ipv6Tunnel {
			if err := ctx.permitInterfaceIPv6(weightPermit, opts.tunLUID); err != nil {
				return err
			}
		}

		// 6. Permit IPv4 DHCP (68<->67) so the physical link/hotspot lease survives.
		if err := ctx.permitDHCPIPv4(weightPermit); err != nil {
			return err
		}

		// 7. Optional user exclusions.
		if opts.allowDiscovery {
			if err := ctx.permitRelayDiscovery(weightPermit, opts.discoveryApp); err != nil {
				return err
			}
		}
		if opts.allowLAN {
			if err := ctx.permitCIDRs(weightPermit, "Relay: permit excluded LAN prefix", lanPrefixes); err != nil {
				return err
			}
		}
		if len(opts.excludePrefixes) > 0 {
			if err := ctx.permitCIDRs(weightPermit, "Relay: permit user-excluded CIDR", opts.excludePrefixes); err != nil {
				return err
			}
		}
		for _, appPath := range opts.excludeApps {
			if err := ctx.permitExcludedApp(weightPermit, appPath); err != nil {
				return err
			}
		}

		// 8. Blanket default-deny across all four ALE layers (connect + recv-accept v4/v6).
		return ctx.blockAll(weightBlock)
	}); err != nil {
		runtime.KeepAlive(pins)
		procFwpmEngineClose0.Call(engine)
		return nil, err
	}

	runtime.KeepAlive(pins)

	shutdown := func() {
		if opts.persistent {
			_ = runWfpTransaction(engine, func() error {
				return deletePersistentObjectsInTx(engine)
			})
		}
		procFwpmEngineClose0.Call(engine)
	}
	return shutdown, nil
}

// clearPersistentKillSwitch removes any persistent Relay WFP filters, sublayer,
// and provider left behind by -persist-killswitch. Safe when nothing exists.
func clearPersistentKillSwitch() error {
	engine, err := openWfpEngine("Relay", "Relay clear persistent kill-switch", 0)
	if err != nil {
		return err
	}
	defer procFwpmEngineClose0.Call(engine)

	return runWfpTransaction(engine, func() error {
		return deletePersistentObjectsInTx(engine)
	})
}

func openWfpEngine(nameStr, descStr string, flags uint32) (uintptr, error) {
	name, err := windows.UTF16PtrFromString(nameStr)
	if err != nil {
		return 0, err
	}
	description, err := windows.UTF16PtrFromString(descStr)
	if err != nil {
		return 0, err
	}
	session := fwpmSession0{
		displayData:          fwpmDisplayData0{name: name, description: description},
		flags:                flags,
		txnWaitTimeoutInMSec: windows.INFINITE,
	}
	var engine uintptr
	ret, _, _ := procFwpmEngineOpen0.Call(
		0, rpcCAuthnWinNT, 0,
		uintptr(unsafe.Pointer(&session)),
		uintptr(unsafe.Pointer(&engine)),
	)
	runtime.KeepAlive(name)
	runtime.KeepAlive(description)
	runtime.KeepAlive(&session)
	if ret != 0 {
		return 0, fmt.Errorf("FwpmEngineOpen0: %w", windows.Errno(ret))
	}
	return engine, nil
}

func runWfpTransaction(engine uintptr, fn func() error) error {
	ret, _, _ := procFwpmTransactionBegin0.Call(engine, 0)
	if ret != 0 {
		return fmt.Errorf("FwpmTransactionBegin0: %w", windows.Errno(ret))
	}
	if err := fn(); err != nil {
		procFwpmTransactionAbort0.Call(engine)
		return err
	}
	ret, _, _ = procFwpmTransactionCommit0.Call(engine)
	if ret != 0 {
		procFwpmTransactionAbort0.Call(engine)
		return fmt.Errorf("FwpmTransactionCommit0: %w", windows.Errno(ret))
	}
	return nil
}

func registerProviderAndSublayer(
	engine uintptr,
	providerKey, sublayerKey windows.GUID,
	flags uint32,
	pins *[]any,
) error {
	provName, _ := windows.UTF16PtrFromString("Relay")
	provDesc, _ := windows.UTF16PtrFromString("Relay leak protection provider")
	*pins = append(*pins, provName, provDesc)

	provider := fwpmProvider0{
		providerKey: providerKey,
		displayData: fwpmDisplayData0{name: provName, description: provDesc},
		flags:       flags,
	}
	ret, _, _ := procFwpmProviderAdd0.Call(engine, uintptr(unsafe.Pointer(&provider)), 0)
	runtime.KeepAlive(&provider)
	if ret != 0 {
		return fmt.Errorf("FwpmProviderAdd0: %w", windows.Errno(ret))
	}

	subName, _ := windows.UTF16PtrFromString("Relay filters")
	subDesc, _ := windows.UTF16PtrFromString("Relay default-deny leak protection and kill-switch sublayer")
	*pins = append(*pins, subName, subDesc)

	sublayer := fwpmSublayer0{
		subLayerKey: sublayerKey,
		displayData: fwpmDisplayData0{name: subName, description: subDesc},
		flags:       flags,
		providerKey: &providerKey,
		weight:      ^uint16(0),
	}
	ret, _, _ = procFwpmSubLayerAdd0.Call(engine, uintptr(unsafe.Pointer(&sublayer)), 0)
	runtime.KeepAlive(&sublayer)
	if ret != 0 {
		return fmt.Errorf("FwpmSubLayerAdd0: %w", windows.Errno(ret))
	}
	return nil
}

func deletePersistentObjectsInTx(engine uintptr) error {
	var enumHandle uintptr
	ret, _, _ := procFwpmFilterCreateEnumHandle0.Call(
		engine, 0, uintptr(unsafe.Pointer(&enumHandle)),
	)
	if ret != 0 {
		return fmt.Errorf("FwpmFilterCreateEnumHandle0: %w", windows.Errno(ret))
	}

	var toDelete []windows.GUID
	for {
		var entries **fwpmFilter0
		var num uint32
		ret, _, _ := procFwpmFilterEnum0.Call(
			engine,
			enumHandle,
			256,
			uintptr(unsafe.Pointer(&entries)),
			uintptr(unsafe.Pointer(&num)),
		)
		if ret != 0 {
			procFwpmFilterDestroyEnum0.Call(engine, enumHandle)
			return fmt.Errorf("FwpmFilterEnum0: %w", windows.Errno(ret))
		}
		if num == 0 {
			if entries != nil {
				procFwpmFreeMemory0.Call(uintptr(unsafe.Pointer(&entries)))
			}
			break
		}
		slice := unsafe.Slice(entries, num)
		for _, f := range slice {
			if f != nil && f.subLayerKey == relaySublayerKey {
				toDelete = append(toDelete, f.filterKey)
			}
		}
		procFwpmFreeMemory0.Call(uintptr(unsafe.Pointer(&entries)))
		if num < 256 {
			break
		}
	}
	procFwpmFilterDestroyEnum0.Call(engine, enumHandle)

	for i := range toDelete {
		ret, _, _ := procFwpmFilterDeleteByKey0.Call(engine, uintptr(unsafe.Pointer(&toDelete[i])))
		if ret != 0 && uint32(ret) != uint32(windows.FWP_E_FILTER_NOT_FOUND) {
			return fmt.Errorf("FwpmFilterDeleteByKey0: %w", windows.Errno(ret))
		}
	}

	ret, _, _ = procFwpmSubLayerDeleteByKey0.Call(engine, uintptr(unsafe.Pointer(&relaySublayerKey)))
	if ret != 0 && uint32(ret) != uint32(windows.FWP_E_SUBLAYER_NOT_FOUND) && uint32(ret) != uint32(windows.FWP_E_NOT_FOUND) {
		return fmt.Errorf("FwpmSubLayerDeleteByKey0: %w", windows.Errno(ret))
	}

	ret, _, _ = procFwpmProviderDeleteByKey0.Call(engine, uintptr(unsafe.Pointer(&relayProviderKey)))
	if ret != 0 && uint32(ret) != uint32(windows.FWP_E_PROVIDER_NOT_FOUND) && uint32(ret) != uint32(windows.FWP_E_NOT_FOUND) {
		return fmt.Errorf("FwpmProviderDeleteByKey0: %w", windows.Errno(ret))
	}

	return nil
}

type wfpRuleContext struct {
	engine      uintptr
	providerKey *windows.GUID
	sublayerKey windows.GUID
	baseFlags   uint32
	pins        *[]any
	appIDBlobs  *[]*fwpByteBlob
}

func (c *wfpRuleContext) addFilter(
	nameStr string,
	layer windows.GUID,
	action uint32,
	weight uint8,
	extraFlags uint32,
	conditions []fwpmFilterCondition0,
) error {
	name, err := windows.UTF16PtrFromString(nameStr)
	if err != nil {
		return err
	}
	*c.pins = append(*c.pins, name, conditions)

	filter := fwpmFilter0{
		displayData: fwpmDisplayData0{name: name},
		flags:       c.baseFlags | extraFlags,
		providerKey: c.providerKey,
		layerKey:    layer,
		subLayerKey: c.sublayerKey,
		weight: fwpValue0{
			kind:  fwpUint8,
			value: uintptr(weight),
		},
		action: fwpmAction0{kind: action},
	}
	if len(conditions) > 0 {
		filter.numFilterConditions = uint32(len(conditions))
		filter.filterCondition = &conditions[0]
	}

	var id uint64
	ret, _, _ := procFwpmFilterAdd0.Call(
		c.engine,
		uintptr(unsafe.Pointer(&filter)),
		0,
		uintptr(unsafe.Pointer(&id)),
	)
	runtime.KeepAlive(&filter)
	runtime.KeepAlive(conditions)
	runtime.KeepAlive(name)
	if ret != 0 {
		return fmt.Errorf("adding filter %q: %w", nameStr, windows.Errno(ret))
	}
	return nil
}

func (c *wfpRuleContext) appIDBlob(exePath string) (*fwpByteBlob, error) {
	pathPtr, err := windows.UTF16PtrFromString(exePath)
	if err != nil {
		return nil, err
	}
	var blob *fwpByteBlob
	ret, _, _ := procFwpmGetAppIdFromFileName0.Call(
		uintptr(unsafe.Pointer(pathPtr)),
		uintptr(unsafe.Pointer(&blob)),
	)
	runtime.KeepAlive(pathPtr)
	if ret != 0 {
		return nil, fmt.Errorf("FwpmGetAppIdFromFileName0(%q): %w", exePath, windows.Errno(ret))
	}
	*c.appIDBlobs = append(*c.appIDBlobs, blob)
	return blob, nil
}

func (c *wfpRuleContext) permitLoopback(weight uint8) error {
	cond := []fwpmFilterCondition0{{
		fieldKey:  conditionFlags,
		matchType: fwpMatchFlagsAllSet,
		conditionValue: fwpConditionValue0{
			kind:  fwpUint32,
			value: uintptr(fwpConditionFlagIsLoopback),
		},
	}}
	for _, layer := range []windows.GUID{
		layerAleAuthConnectV4,
		layerAleAuthRecvAcceptV4,
		layerAleAuthConnectV6,
		layerAleAuthRecvAcceptV6,
	} {
		if err := c.addFilter("Relay: permit loopback", layer, fwpActionPermit, weight, 0, cond); err != nil {
			return err
		}
	}
	return nil
}

func (c *wfpRuleContext) permitSelfWireGuardUDP(weight uint8) error {
	selfPath, err := os.Executable()
	if err != nil {
		return fmt.Errorf("locating current executable: %w", err)
	}
	appID, err := c.appIDBlob(selfPath)
	if err != nil {
		return err
	}
	cond := []fwpmFilterCondition0{
		{
			fieldKey:  conditionAleAppID,
			matchType: fwpMatchEqual,
			conditionValue: fwpConditionValue0{
				kind:  fwpByteBlobType,
				value: uintptr(unsafe.Pointer(appID)),
			},
		},
		{
			fieldKey:  conditionIPProtocol,
			matchType: fwpMatchEqual,
			conditionValue: fwpConditionValue0{
				kind:  fwpUint8,
				value: uintptr(ipProtoUDP),
			},
		},
	}
	for _, layer := range []windows.GUID{layerAleAuthConnectV4, layerAleAuthRecvAcceptV4} {
		if err := c.addFilter(
			"Relay: permit relaywg-client WireGuard UDP",
			layer,
			fwpActionPermit,
			weight,
			fwpmFilterFlagClearActionRight,
			cond,
		); err != nil {
			return err
		}
	}
	return nil
}

func (c *wfpRuleContext) permitTunnelDNS(weight uint8, tunLUID uint64, resolvers []netip.Addr) error {
	if len(resolvers) == 0 {
		return nil
	}
	luidPtr := new(uint64)
	*luidPtr = tunLUID
	*c.pins = append(*c.pins, luidPtr)

	for _, resolver := range resolvers {
		if resolver.Is4() {
			addrHost := ipv4HostUint32(resolver)
			cond := []fwpmFilterCondition0{
				{
					fieldKey:  conditionIPLocalInterface,
					matchType: fwpMatchEqual,
					conditionValue: fwpConditionValue0{
						kind:  fwpUint64,
						value: uintptr(unsafe.Pointer(luidPtr)),
					},
				},
				{
					fieldKey:  conditionIPRemotePort,
					matchType: fwpMatchEqual,
					conditionValue: fwpConditionValue0{
						kind:  fwpUint16,
						value: uintptr(dnsPort),
					},
				},
				{
					fieldKey:  conditionIPProtocol,
					matchType: fwpMatchEqual,
					conditionValue: fwpConditionValue0{
						kind:  fwpUint8,
						value: uintptr(ipProtoUDP),
					},
				},
				{
					fieldKey:  conditionIPProtocol,
					matchType: fwpMatchEqual,
					conditionValue: fwpConditionValue0{
						kind:  fwpUint8,
						value: uintptr(ipProtoTCP),
					},
				},
				{
					fieldKey:  conditionIPRemoteAddress,
					matchType: fwpMatchEqual,
					conditionValue: fwpConditionValue0{
						kind:  fwpUint32,
						value: uintptr(addrHost),
					},
				},
			}
			for _, layer := range []windows.GUID{layerAleAuthConnectV4, layerAleAuthRecvAcceptV4} {
				if err := c.addFilter(
					fmt.Sprintf("Relay: permit tunnel DNS (%s)", resolver),
					layer,
					fwpActionPermit,
					weight,
					0,
					cond,
				); err != nil {
					return err
				}
			}
		} else if resolver.Is6() {
			maskSpec := &fwpV6AddrAndMask{
				addr:         resolver.As16(),
				prefixLength: 128,
			}
			*c.pins = append(*c.pins, maskSpec)
			cond := []fwpmFilterCondition0{
				{
					fieldKey:  conditionIPLocalInterface,
					matchType: fwpMatchEqual,
					conditionValue: fwpConditionValue0{
						kind:  fwpUint64,
						value: uintptr(unsafe.Pointer(luidPtr)),
					},
				},
				{
					fieldKey:  conditionIPRemotePort,
					matchType: fwpMatchEqual,
					conditionValue: fwpConditionValue0{
						kind:  fwpUint16,
						value: uintptr(dnsPort),
					},
				},
				{
					fieldKey:  conditionIPProtocol,
					matchType: fwpMatchEqual,
					conditionValue: fwpConditionValue0{
						kind:  fwpUint8,
						value: uintptr(ipProtoUDP),
					},
				},
				{
					fieldKey:  conditionIPProtocol,
					matchType: fwpMatchEqual,
					conditionValue: fwpConditionValue0{
						kind:  fwpUint8,
						value: uintptr(ipProtoTCP),
					},
				},
				{
					fieldKey:  conditionIPRemoteAddress,
					matchType: fwpMatchEqual,
					conditionValue: fwpConditionValue0{
						kind:  fwpV6AddrMask,
						value: uintptr(unsafe.Pointer(maskSpec)),
					},
				},
			}
			for _, layer := range []windows.GUID{layerAleAuthConnectV6, layerAleAuthRecvAcceptV6} {
				if err := c.addFilter(
					fmt.Sprintf("Relay: permit tunnel DNSv6 (%s)", resolver),
					layer,
					fwpActionPermit,
					weight,
					0,
					cond,
				); err != nil {
					return err
				}
			}
		}
	}
	return nil
}

func (c *wfpRuleContext) blockNameResolution(weight uint8) error {
	allLayers := []windows.GUID{
		layerAleAuthConnectV4,
		layerAleAuthRecvAcceptV4,
		layerAleAuthConnectV6,
		layerAleAuthRecvAcceptV6,
	}
	v4Layers := []windows.GUID{
		layerAleAuthConnectV4,
		layerAleAuthRecvAcceptV4,
	}

	portProtoBlock := func(name string, port uint16, tcp bool, layers []windows.GUID) error {
		cond := make([]fwpmFilterCondition0, 0, 3)
		cond = append(cond,
			fwpmFilterCondition0{
				fieldKey:  conditionIPRemotePort,
				matchType: fwpMatchEqual,
				conditionValue: fwpConditionValue0{
					kind:  fwpUint16,
					value: uintptr(port),
				},
			},
			fwpmFilterCondition0{
				fieldKey:  conditionIPProtocol,
				matchType: fwpMatchEqual,
				conditionValue: fwpConditionValue0{
					kind:  fwpUint8,
					value: uintptr(ipProtoUDP),
				},
			},
		)
		if tcp {
			cond = append(cond, fwpmFilterCondition0{
				fieldKey:  conditionIPProtocol,
				matchType: fwpMatchEqual,
				conditionValue: fwpConditionValue0{
					kind:  fwpUint8,
					value: uintptr(ipProtoTCP),
				},
			})
		}
		for _, layer := range layers {
			if err := c.addFilter(name, layer, fwpActionBlock, weight, 0, cond); err != nil {
				return err
			}
		}
		return nil
	}

	if err := portProtoBlock("Relay: block non-tunnel DNS", dnsPort, true, allLayers); err != nil {
		return err
	}
	if err := portProtoBlock("Relay: block mDNS", mdnsPort, false, allLayers); err != nil {
		return err
	}
	if err := portProtoBlock("Relay: block LLMNR", llmnrPort, true, allLayers); err != nil {
		return err
	}
	return portProtoBlock("Relay: block NetBIOS-NS", netbiosPort, false, v4Layers)
}

func (c *wfpRuleContext) permitInterfaceIPv4(weight uint8, tunLUID uint64) error {
	luidPtr := new(uint64)
	*luidPtr = tunLUID
	*c.pins = append(*c.pins, luidPtr)

	cond := []fwpmFilterCondition0{{
		fieldKey:  conditionIPLocalInterface,
		matchType: fwpMatchEqual,
		conditionValue: fwpConditionValue0{
			kind:  fwpUint64,
			value: uintptr(unsafe.Pointer(luidPtr)),
		},
	}}
	for _, layer := range []windows.GUID{layerAleAuthConnectV4, layerAleAuthRecvAcceptV4} {
		if err := c.addFilter("Relay: permit WinTun IPv4 traffic", layer, fwpActionPermit, weight, 0, cond); err != nil {
			return err
		}
	}
	return nil
}

func (c *wfpRuleContext) permitInterfaceIPv6(weight uint8, tunLUID uint64) error {
	luidPtr := new(uint64)
	*luidPtr = tunLUID
	*c.pins = append(*c.pins, luidPtr)

	cond := []fwpmFilterCondition0{{
		fieldKey:  conditionIPLocalInterface,
		matchType: fwpMatchEqual,
		conditionValue: fwpConditionValue0{
			kind:  fwpUint64,
			value: uintptr(unsafe.Pointer(luidPtr)),
		},
	}}
	for _, layer := range []windows.GUID{layerAleAuthConnectV6, layerAleAuthRecvAcceptV6} {
		if err := c.addFilter("Relay: permit WinTun IPv6 traffic", layer, fwpActionPermit, weight, 0, cond); err != nil {
			return err
		}
	}
	return nil
}

func (c *wfpRuleContext) permitDHCPIPv4(weight uint8) error {
	outbound := []fwpmFilterCondition0{
		{
			fieldKey:  conditionIPProtocol,
			matchType: fwpMatchEqual,
			conditionValue: fwpConditionValue0{
				kind:  fwpUint8,
				value: uintptr(ipProtoUDP),
			},
		},
		{
			fieldKey:  conditionIPLocalPort,
			matchType: fwpMatchEqual,
			conditionValue: fwpConditionValue0{
				kind:  fwpUint16,
				value: uintptr(68),
			},
		},
		{
			fieldKey:  conditionIPRemotePort,
			matchType: fwpMatchEqual,
			conditionValue: fwpConditionValue0{
				kind:  fwpUint16,
				value: uintptr(67),
			},
		},
		{
			fieldKey:  conditionIPRemoteAddress,
			matchType: fwpMatchEqual,
			conditionValue: fwpConditionValue0{
				kind:  fwpUint32,
				value: uintptr(0xffffffff),
			},
		},
	}
	if err := c.addFilter("Relay: permit outbound DHCPv4", layerAleAuthConnectV4, fwpActionPermit, weight, 0, outbound); err != nil {
		return err
	}

	inbound := outbound[:3]
	return c.addFilter("Relay: permit inbound DHCPv4", layerAleAuthRecvAcceptV4, fwpActionPermit, weight, 0, inbound)
}

func (c *wfpRuleContext) permitRelayDiscovery(weight uint8, explicitPath string) error {
	appPath := resolveDiscoveryAppPath(explicitPath)
	if appPath == "" {
		fmt.Fprintln(os.Stderr, "skipping -allow-discovery rule: Relay UI executable not found beside relaywg-client")
		return nil
	}
	appID, err := c.appIDBlob(appPath)
	if err != nil {
		return err
	}
	outCond := []fwpmFilterCondition0{
		{
			fieldKey:  conditionAleAppID,
			matchType: fwpMatchEqual,
			conditionValue: fwpConditionValue0{
				kind:  fwpByteBlobType,
				value: uintptr(unsafe.Pointer(appID)),
			},
		},
		{
			fieldKey:  conditionIPProtocol,
			matchType: fwpMatchEqual,
			conditionValue: fwpConditionValue0{
				kind:  fwpUint8,
				value: uintptr(ipProtoUDP),
			},
		},
		{
			fieldKey:  conditionIPRemotePort,
			matchType: fwpMatchEqual,
			conditionValue: fwpConditionValue0{
				kind:  fwpUint16,
				value: uintptr(discoveryPort),
			},
		},
	}
	if err := c.addFilter("Relay: permit Relay beacon discovery outbound", layerAleAuthConnectV4, fwpActionPermit, weight, 0, outCond); err != nil {
		return err
	}

	inCond := []fwpmFilterCondition0{
		outCond[0],
		outCond[1],
		{
			fieldKey:  conditionIPLocalPort,
			matchType: fwpMatchEqual,
			conditionValue: fwpConditionValue0{
				kind:  fwpUint16,
				value: uintptr(discoveryPort),
			},
		},
	}
	return c.addFilter("Relay: permit Relay beacon discovery inbound", layerAleAuthRecvAcceptV4, fwpActionPermit, weight, 0, inCond)
}

func resolveDiscoveryAppPath(explicit string) string {
	candidates := make([]string, 0, 3)
	if explicit != "" {
		candidates = append(candidates, explicit)
	}
	if self, err := os.Executable(); err == nil {
		dir := filepath.Dir(self)
		candidates = append(candidates,
			filepath.Join(dir, "Relay.App.exe"),
			filepath.Join(dir, "Relay.exe"),
		)
	}
	for _, p := range candidates {
		if info, err := os.Stat(p); err == nil && !info.IsDir() {
			return p
		}
	}
	return ""
}

func (c *wfpRuleContext) permitCIDRs(weight uint8, label string, prefixes []netip.Prefix) error {
	for _, p := range prefixes {
		p = p.Masked()
		if p.Addr().Is4() {
			maskSpec := &fwpV4AddrAndMask{
				addr: ipv4HostUint32(p.Addr()),
				mask: ipv4PrefixMask(p.Bits()),
			}
			*c.pins = append(*c.pins, maskSpec)
			cond := []fwpmFilterCondition0{{
				fieldKey:  conditionIPRemoteAddress,
				matchType: fwpMatchEqual,
				conditionValue: fwpConditionValue0{
					kind:  fwpV4AddrMask,
					value: uintptr(unsafe.Pointer(maskSpec)),
				},
			}}
			for _, layer := range []windows.GUID{layerAleAuthConnectV4, layerAleAuthRecvAcceptV4} {
				if err := c.addFilter(fmt.Sprintf("%s (%s)", label, p), layer, fwpActionPermit, weight, 0, cond); err != nil {
					return err
				}
			}
		} else if p.Addr().Is6() {
			maskSpec := &fwpV6AddrAndMask{
				addr:         p.Addr().As16(),
				prefixLength: uint8(p.Bits()),
			}
			*c.pins = append(*c.pins, maskSpec)
			cond := []fwpmFilterCondition0{{
				fieldKey:  conditionIPRemoteAddress,
				matchType: fwpMatchEqual,
				conditionValue: fwpConditionValue0{
					kind:  fwpV6AddrMask,
					value: uintptr(unsafe.Pointer(maskSpec)),
				},
			}}
			for _, layer := range []windows.GUID{layerAleAuthConnectV6, layerAleAuthRecvAcceptV6} {
				if err := c.addFilter(fmt.Sprintf("%s (%s)", label, p), layer, fwpActionPermit, weight, 0, cond); err != nil {
					return err
				}
			}
		}
	}
	return nil
}

func (c *wfpRuleContext) permitExcludedApp(weight uint8, appPath string) error {
	info, err := os.Stat(appPath)
	if err != nil || info.IsDir() {
		fmt.Fprintf(os.Stderr, "skipping missing excluded executable %q\n", appPath)
		return nil
	}
	appID, err := c.appIDBlob(appPath)
	if err != nil {
		return err
	}
	cond := []fwpmFilterCondition0{{
		fieldKey:  conditionAleAppID,
		matchType: fwpMatchEqual,
		conditionValue: fwpConditionValue0{
			kind:  fwpByteBlobType,
			value: uintptr(unsafe.Pointer(appID)),
		},
	}}
	// Excluded apps get IPv4 outside-tunnel access only; IPv6 stays blocked
	// and DNS/mDNS/LLMNR/NetBIOS remain blocked at weight 14.
	for _, layer := range []windows.GUID{layerAleAuthConnectV4, layerAleAuthRecvAcceptV4} {
		if err := c.addFilter(fmt.Sprintf("Relay: permit excluded app (%s)", filepath.Base(appPath)), layer, fwpActionPermit, weight, 0, cond); err != nil {
			return err
		}
	}
	return nil
}

func (c *wfpRuleContext) blockAll(weight uint8) error {
	for _, layer := range []windows.GUID{
		layerAleAuthConnectV4,
		layerAleAuthRecvAcceptV4,
		layerAleAuthConnectV6,
		layerAleAuthRecvAcceptV6,
	} {
		if err := c.addFilter("Relay: block all non-tunnel traffic", layer, fwpActionBlock, weight, 0, nil); err != nil {
			return err
		}
	}
	return nil
}

func ipv4HostUint32(addr netip.Addr) uint32 {
	b := addr.As4()
	return binary.BigEndian.Uint32(b[:])
}

func ipv4PrefixMask(bits int) uint32 {
	if bits <= 0 {
		return 0
	}
	if bits >= 32 {
		return 0xffffffff
	}
	return ^uint32(0) << (32 - bits)
}
