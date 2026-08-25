//go:build amd64 || arm64

package main

// Leak protection, written against the Windows Filtering Platform directly.
//
// Why not wireguard-windows' firewall package, which does this already: its
// permitWireGuardService requires a service SID in the calling process's token
// (helpers.go looks for a token group whose first sub-authority is 80, i.e.
// NT SERVICE\...). WireGuard runs its tunnel as a Windows service and has one.
// Relay runs this as an elevated *user* process on purpose (ADR-0005), so the
// token has no such group and EnableFirewall returns ERROR_NO_SUCH_GROUP before
// installing a single filter. That was shipped once, verified on hardware to do
// nothing at all, and is the reason this file exists.
//
// It is also narrower than what that package installs, and deliberately so.
// WireGuard blocks everything and then permits its own process, which is why it
// needs to identify itself. Relay blocks only the two things that were actually
// escaping — DNS to resolvers other than the tunnel's, and IPv6, which this
// client does not carry — and leaves the rest of the machine alone. That has a
// consequence worth stating: Relay's own discovery keeps working, so following
// a phone that changes address still works, which a blanket block would have
// killed.
//
// One exception is carved back out: loopback is permitted above both blocks.
// "Block all of ALE_AUTH_CONNECT_V6" turned out to mean all of it, loopback
// included, and on Windows "localhost" resolves to ::1 before 127.0.0.1 — so
// the first version of this broke every localhost connection on the machine
// for as long as Relay was connected. Permitting it cannot leak, because
// loopback never reaches a network interface for anything to escape by.
// TestLeakProtectionLeavesLoopbackAlone fails without it.
//
// The filters live in Relay's own sublayer, registered at the maximum weight.
// They used to live in FWPM_SUBLAYER_UNIVERSAL, which avoided registering one
// and also avoided working: that is where Windows Firewall keeps its own
// filters, several of which permit outbound traffic at weights far above
// anything set here, and within a sublayer the highest weight wins. The DNS
// block was installed, reported success, and lost every arbitration it entered
// -- which is why a leak test still listed the local resolver. Measured, not
// deduced: TestLeakProtectionBlocksWhatItShouldAndNothingElse probes each rule
// with the tunnel down and again with it up, and asserts on the difference.
//
// Every filter is added inside a session opened with FWPM_SESSION_FLAG_DYNAMIC.
// Windows destroys the whole session, and with it every filter, when this
// process ends — including when it is killed or crashes. That is the same
// property the WinTun adapter has, and it is what makes failing closed safe: a
// dead Relay cannot leave a machine that resolves no names.
//
// 32-bit is excluded by the build tag above. FWPM_FILTER0 needs a different
// layout correction there, and shipping a struct layout that has not been
// checked against a real kernel is not worth doing for a build the README
// already describes as "only if you know you need it". On 386 the caller falls
// back to reporting that protection is unavailable, which is honest.

import (
	"fmt"
	"net/netip"
	"runtime"
	"unsafe"

	"golang.org/x/sys/windows"
)

// Layouts and constants below mirror fwpmtypes.h. The sizes are asserted at
// compile time against the values wireguard-windows verifies against a real
// kernel in its own test suite; a mismatch is a build failure rather than a
// pointer handed to a driver.
const (
	wtFwpmFilter0Size64   = 200
	wtFwpmSession0Size64  = 72
	wtFwpmSublayer0Size64 = 72
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
	kind  uint32
	_     [4]byte
	value uintptr
}

type fwpConditionValue0 struct {
	kind  uint32
	_     [4]byte
	value uintptr
}

type fwpmFilterCondition0 struct {
	fieldKey       windows.GUID
	matchType      uint32
	_              [4]byte
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
	_                    [4]byte
	sid                  *windows.SID
	username             *uint16
	kernelMode           uint8
	_                    [7]byte
}

type fwpmSublayer0 struct {
	subLayerKey  windows.GUID
	displayData  fwpmDisplayData0
	flags        uint32
	_            [4]byte
	providerKey  *windows.GUID
	providerData fwpByteBlob
	weight       uint16
	_            [6]byte
}

type fwpmFilter0 struct {
	filterKey           windows.GUID
	displayData         fwpmDisplayData0
	flags               uint32
	_                   [4]byte
	providerKey         *windows.GUID
	providerData        fwpByteBlob
	layerKey            windows.GUID
	subLayerKey         windows.GUID
	weight              fwpValue0
	numFilterConditions uint32
	_                   [4]byte
	filterCondition     *fwpmFilterCondition0
	action              fwpmAction0
	// The correction wireguard-windows also needs: FWPM_FILTER0 is 8-aligned
	// because it carries a UINT64, so the GUID after the 20-byte action starts
	// at 152 rather than the 148 Go would choose on its own.
	_                  [4]byte
	providerContextKey windows.GUID
	reserved           *windows.GUID
	filterID           uint64
	effectiveWeight    fwpValue0
}

// A layout mistake here would be a pointer into the kernel with the wrong
// shape. These fail the build instead.
var _ [1]struct{} = [unsafe.Sizeof(fwpmFilter0{}) - wtFwpmFilter0Size64 + 1]struct{}{}
var _ [1]struct{} = [unsafe.Sizeof(fwpmSession0{}) - wtFwpmSession0Size64 + 1]struct{}{}
var _ [1]struct{} = [unsafe.Sizeof(fwpmSublayer0{}) - wtFwpmSublayer0Size64 + 1]struct{}{}

const (
	fwpActionFlagTerminating = 0x00001000
	fwpActionBlock           = 0x00000001 | fwpActionFlagTerminating
	fwpActionPermit          = 0x00000002 | fwpActionFlagTerminating

	fwpMatchEqual = 0
	// FWP_MATCH_FLAGS_ALL_SET, the seventh member of FWP_MATCH_TYPE.
	fwpMatchFlagsAllSet = 6

	fwpUint8  = 1
	fwpUint16 = 2
	fwpUint32 = 3

	// FWP_CONDITION_FLAG_IS_LOOPBACK: the packet never leaves the machine.
	fwpConditionFlagIsLoopback = 0x00000001

	fwpmSessionFlagDynamic = 0x00000001
	rpcCAuthnWinNT         = 10

	dnsPort = 53
)

var (
	// FWPM_LAYER_ALE_AUTH_CONNECT_V4 / _V6: outbound connect, which is where a
	// UDP send and a TCP connect are both authorised.
	layerAleAuthConnectV4 = windows.GUID{
		Data1: 0xc38d57d1, Data2: 0x05a7, Data3: 0x4c33,
		Data4: [8]byte{0x90, 0x4f, 0x7f, 0xbc, 0xee, 0xe6, 0x0e, 0x82},
	}
	layerAleAuthConnectV6 = windows.GUID{
		Data1: 0x4a72393b, Data2: 0x319f, Data3: 0x44bc,
		Data4: [8]byte{0x84, 0xc3, 0xba, 0x54, 0xdc, 0xb3, 0xb6, 0xb4},
	}
	conditionIPRemotePort = windows.GUID{
		Data1: 0xc35a604d, Data2: 0xd22b, Data3: 0x4e1a,
		Data4: [8]byte{0x91, 0xb4, 0x68, 0xf6, 0x74, 0xee, 0x67, 0x4b},
	}
	conditionIPRemoteAddress = windows.GUID{
		Data1: 0xb235ae9a, Data2: 0x1d64, Data3: 0x49b8,
		Data4: [8]byte{0xa4, 0x4c, 0x5f, 0xf3, 0xd9, 0x09, 0x50, 0x45},
	}
	// FWPM_CONDITION_FLAGS, which carries FWP_CONDITION_FLAG_IS_LOOPBACK.
	conditionFlags = windows.GUID{
		Data1: 0x632ce23b, Data2: 0x5167, Data3: 0x435c,
		Data4: [8]byte{0x86, 0xd7, 0xe9, 0x03, 0x68, 0x4a, 0xa8, 0x0c},
	}
	// Relay's own sublayer.
	//
	// This used to be FWPM_SUBLAYER_UNIVERSAL, on the reasoning that using the
	// built-in sublayer avoided registering one. It avoided the registration
	// and it also avoided working: the universal sublayer is where Windows
	// Firewall keeps its own filters, several of which permit outbound traffic
	// at weights far above anything set here, and within a sublayer the highest
	// weight wins. So the DNS block sat there, correctly installed, losing
	// every arbitration it entered.
	//
	// Sublayers are arbitrated before the filters inside them, and this one is
	// registered at the maximum weight, so a block here beats a permit in the
	// universal sublayer. Traffic this sublayer says nothing about still falls
	// through to Windows Firewall exactly as before, which is what keeps this
	// from becoming a second firewall.
	sublayerRelay = windows.GUID{
		Data1: 0x2171bbec, Data2: 0xcdd2, Data3: 0x4667,
		Data4: [8]byte{0x81, 0x80, 0x4e, 0x48, 0xc2, 0x10, 0x1b, 0x71},
	}
)

var (
	fwpuclnt             = windows.NewLazySystemDLL("fwpuclnt.dll")
	procFwpmEngineOpen0  = fwpuclnt.NewProc("FwpmEngineOpen0")
	procFwpmEngineClose  = fwpuclnt.NewProc("FwpmEngineClose0")
	procFwpmFilterAdd0   = fwpuclnt.NewProc("FwpmFilterAdd0")
	procFwpmSubLayerAdd0 = fwpuclnt.NewProc("FwpmSubLayerAdd0")
)

// enableLeakProtection blocks the two ways traffic was leaving around the
// tunnel, and returns a function that closes the session early.
//
// Calling the returned function is optional: the session is dynamic, so
// Windows tears it down when the process exits by any route. It exists so a
// clean shutdown does not wait on process teardown.
func enableLeakProtection(resolvers []netip.Addr) (func(), error) {
	name, _ := windows.UTF16PtrFromString("Relay")
	description, _ := windows.UTF16PtrFromString("Relay leak protection (dynamic)")

	session := fwpmSession0{
		displayData: fwpmDisplayData0{name: name, description: description},
		flags:       fwpmSessionFlagDynamic,
	}

	var engine uintptr
	ret, _, _ := procFwpmEngineOpen0.Call(
		0, rpcCAuthnWinNT, 0,
		uintptr(unsafe.Pointer(&session)),
		uintptr(unsafe.Pointer(&engine)),
	)
	if ret != 0 {
		return nil, fmt.Errorf("opening a filtering session: %w", windows.Errno(ret))
	}
	shutdown := func() { procFwpmEngineClose.Call(engine) }

	// The sublayer has to exist before anything is put in it. Registered inside
	// the dynamic session, so Windows removes it with everything else when this
	// process ends, by any route.
	if err := addSublayer(engine); err != nil {
		shutdown()
		return nil, err
	}

	// Weights are compared within the sublayer; the permits must outrank the
	// blocks or the tunnel's own resolver would be blocked along with the rest.
	const (
		weightBlock    = 8
		weightPermit   = 12
		weightLoopback = 14
	)

	// Loopback first, and above everything else.
	//
	// The IPv6 block below is written as "all of ALE_AUTH_CONNECT_V6", and that
	// is literally all of it: WFP classifies loopback at this layer too, so it
	// took ::1 with it. On Windows "localhost" resolves to ::1 before
	// 127.0.0.1, which means that while Relay was connected, every localhost
	// connection on the machine -- a development server, a database client, a
	// desktop app talking to its own helper -- either failed outright or sat
	// out a connect attempt before falling back to IPv4. That is not a leak
	// being closed; it is unrelated software being broken, and it made "turn
	// leak protection off" the fix for a machine that had gone slow.
	//
	// Permitting it cannot leak anything. Loopback does not reach a network
	// interface at all, so there is no adapter for it to escape by. This is the
	// same exception wireguard-windows carves out, for the same reason.
	for _, layer := range []windows.GUID{layerAleAuthConnectV4, layerAleAuthConnectV6} {
		if err := addFilter(engine, filterSpec{
			name:     "Relay: permit loopback, which never leaves the machine",
			layer:    layer,
			action:   fwpActionPermit,
			weight:   weightLoopback,
			loopback: true,
		}); err != nil {
			shutdown()
			return nil, err
		}
	}

	// IPv6, all of it. This client configures AF_INET only, so every v6
	// connection was leaving by the physical adapter with the real address on
	// it. There is no v6 inside the tunnel for it to use instead, so the honest
	// thing is to refuse rather than to leak.
	if err := addFilter(engine, filterSpec{
		name:   "Relay: block IPv6, which the tunnel does not carry",
		layer:  layerAleAuthConnectV6,
		action: fwpActionBlock,
		weight: weightBlock,
	}); err != nil {
		shutdown()
		return nil, err
	}

	// DNS to anywhere. Windows resolves names on every interface at once, so on
	// a Wi-Fi shared with the phone the router answered alongside the tunnel and
	// a leak test listed the local ISP. Blocked here, permitted below for the
	// tunnel's own resolver only.
	if err := addFilter(engine, filterSpec{
		name:       "Relay: block DNS outside the tunnel",
		layer:      layerAleAuthConnectV4,
		action:     fwpActionBlock,
		weight:     weightBlock,
		remotePort: dnsPort,
	}); err != nil {
		shutdown()
		return nil, err
	}

	for _, resolver := range resolvers {
		if !resolver.Is4() {
			continue
		}
		if err := addFilter(engine, filterSpec{
			name:       "Relay: permit DNS to the tunnel's resolver",
			layer:      layerAleAuthConnectV4,
			action:     fwpActionPermit,
			weight:     weightPermit,
			remotePort: dnsPort,
			remoteIPv4: resolver,
			hasRemote:  true,
		}); err != nil {
			shutdown()
			return nil, err
		}
	}

	return shutdown, nil
}

// addSublayer registers Relay's own sublayer at the maximum weight.
//
// Sublayers are arbitrated before the filters within them, so this is what
// decides whether a block here can beat Windows Firewall's own permits. Without
// it -- which is how this shipped -- the DNS block was installed correctly,
// reported success, and lost every arbitration it entered.
func addSublayer(engine uintptr) error {
	name, _ := windows.UTF16PtrFromString("Relay")
	description, _ := windows.UTF16PtrFromString("Relay leak protection")

	sublayer := fwpmSublayer0{
		subLayerKey: sublayerRelay,
		displayData: fwpmDisplayData0{name: name, description: description},
		// The highest weight there is. Anything less and the question of whether
		// leak protection works depends on what else is installed on the
		// machine, which is not a property worth having.
		weight: ^uint16(0),
	}

	ret, _, _ := procFwpmSubLayerAdd0.Call(
		engine,
		uintptr(unsafe.Pointer(&sublayer)),
		0,
	)
	runtime.KeepAlive(name)
	runtime.KeepAlive(description)
	if ret != 0 {
		return fmt.Errorf("registering the filtering sublayer: %w", windows.Errno(ret))
	}
	return nil
}

type filterSpec struct {
	name       string
	layer      windows.GUID
	action     uint32
	weight     uint8
	remotePort uint16
	remoteIPv4 netip.Addr
	hasRemote  bool
	// Matches only traffic Windows has already decided is loopback, rather
	// than trusting an address: ::1 is not the only way to reach yourself.
	loopback bool
}

func addFilter(engine uintptr, spec filterSpec) error {
	name, _ := windows.UTF16PtrFromString(spec.name)

	conditions := make([]fwpmFilterCondition0, 0, 3)
	if spec.loopback {
		conditions = append(conditions, fwpmFilterCondition0{
			fieldKey:  conditionFlags,
			matchType: fwpMatchFlagsAllSet,
			conditionValue: fwpConditionValue0{
				kind:  fwpUint32,
				value: uintptr(fwpConditionFlagIsLoopback),
			},
		})
	}
	if spec.remotePort != 0 {
		conditions = append(conditions, fwpmFilterCondition0{
			fieldKey:  conditionIPRemotePort,
			matchType: fwpMatchEqual,
			conditionValue: fwpConditionValue0{
				kind:  fwpUint16,
				value: uintptr(spec.remotePort),
			},
		})
	}
	// Kept alive for the duration of the call: WFP reads through this pointer
	// while FwpmFilterAdd0 runs.
	var addr [4]byte
	if spec.hasRemote {
		addr = spec.remoteIPv4.As4()
		// FWP_UINT32 in host byte order is what this condition expects.
		value := uint32(addr[0])<<24 | uint32(addr[1])<<16 | uint32(addr[2])<<8 | uint32(addr[3])
		conditions = append(conditions, fwpmFilterCondition0{
			fieldKey:  conditionIPRemoteAddress,
			matchType: fwpMatchEqual,
			conditionValue: fwpConditionValue0{
				kind:  fwpUint32,
				value: uintptr(value),
			},
		})
	}

	filter := fwpmFilter0{
		displayData: fwpmDisplayData0{name: name},
		layerKey:    spec.layer,
		subLayerKey: sublayerRelay,
		weight: fwpValue0{
			kind:  fwpUint8,
			value: uintptr(spec.weight),
		},
		action: fwpmAction0{kind: spec.action},
	}
	if len(conditions) > 0 {
		filter.numFilterConditions = uint32(len(conditions))
		filter.filterCondition = &conditions[0]
	}

	var id uint64
	ret, _, _ := procFwpmFilterAdd0.Call(
		engine,
		uintptr(unsafe.Pointer(&filter)),
		0,
		uintptr(unsafe.Pointer(&id)),
	)
	// WFP reads through these while the call runs; nothing may be reclaimed
	// underneath it.
	runtime.KeepAlive(conditions)
	runtime.KeepAlive(addr)
	runtime.KeepAlive(name)
	if ret != 0 {
		return fmt.Errorf("adding filter %q: %w", spec.name, windows.Errno(ret))
	}
	return nil
}
