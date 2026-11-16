// Command relaywg-client is the Windows half of Full Mode: it stands up a
// WinTun adapter and runs a userspace WireGuard tunnel to the phone.
//
// It exists as a separate executable rather than as code inside the app for one
// reason: creating a network adapter and changing routes requires
// Administrator, and Relay is deliberately a per-user install that never asks
// for it (ADR-0005). Putting the privileged part in its own short-lived process
// keeps that property for everything except the mode that genuinely cannot have
// it, and keeps the elevation prompt tied to a single visible action.
//
// The configuration is handed over a stream, never as a file. It carries the
// client's private key, and a key written to a temp file survives a crash, a
// backup, and anyone reading the disk afterwards. A command line is no better:
// any process on the machine can read one.
//
// Protocol with the parent process, over a named pipe (-config-pipe) or over
// stdin and stdout when run by hand:
//
//	in      the wireguard-go IPC configuration, ended by "END-CONFIG"; then
//	        optional control lines ("ENDPOINT <ip:port>", "DISCONNECT")
//	out     "READY" once the peer has handshaked and traffic can flow, or
//	        "NO-HANDSHAKE" if the adapter came up and the peer never answered;
//	        then, only with -hold-on-loss, "PEER-LOST" and "PEER-BACK" as the
//	        phone stops and resumes answering
//	stderr  human-readable progress and errors
//	exit    tear the tunnel down when the stream closes, on "DISCONNECT",
//	        or on Ctrl+Break
package main

import (
	"bufio"
	"errors"
	"flag"
	"fmt"
	"io"
	"net/netip"
	"os"
	"os/signal"
	"strconv"
	"strings"
	"syscall"
	"time"

	"golang.org/x/sys/windows"
	"golang.zx2c4.com/wireguard/device"
	"golang.zx2c4.com/wireguard/tun"
	"golang.zx2c4.com/wireguard/windows/tunnel/winipcfg"
)

const (
	// Matches the endpoint's MTU (/wg/relaywg.go). A mismatch shows up as large
	// replies vanishing while small ones work, which reads like a broken site.
	mtu = 1420

	// Lower than any physical adapter's, so the tunnel wins for the prefixes it
	// is given without having to delete anyone else's routes.
	routeMetric = 5

	// Ends the configuration on stdin without closing it. See [readConfig].
	configTerminator = "END-CONFIG"

	// Graceful disconnect command sent before closing the pipe so persistent
	// kill-switch filters know the shutdown is intentional rather than a crash.
	disconnectCommand = "DISCONNECT"

	// Printed when the filters that keep traffic inside the tunnel could not
	// be installed. With -strict-leak-block (default), the tunnel refuses to
	// come up; with -strict-leak-block=false, it warns and continues.
	leakProtectionFailedLine = "LEAK-PROTECTION-FAILED"

	// Prefix of the line the app sends when the phone turns up at a new address,
	// e.g. "ENDPOINT 192.168.1.14:51820". See [roam].
	endpointPrefix = "ENDPOINT "

	// Printed instead of READY when the adapter came up but the peer never
	// answered.
	noHandshakeLine = "NO-HANDSHAKE"

	// How long to wait for the first handshake before giving up on the peer.
	handshakeTimeout = 20 * time.Second

	// How stale the last handshake may get before the peer counts as gone.
	handshakeStale = 3 * time.Minute

	handshakePoll = 250 * time.Millisecond

	// Printed with -hold-on-loss when the peer goes quiet and again when it
	// answers. See [watchPeer].
	peerLostLine = "PEER-LOST"
	peerBackLine = "PEER-BACK"
)

type runConfig struct {
	name              string
	address           string
	address6          string
	dns               string
	dns6              string
	routes            string
	routes6           string
	wgTcpBridge       string
	emitStats         bool
	pipe              string
	blockLeaks        bool
	strictLeakBlock   bool
	holdOnLoss        bool
	persistKillSwitch bool
	allowLAN          bool
	allowDiscovery    bool
	discoveryApp      string
	excludeApps       []string
	excludePrefixes   []netip.Prefix
}

func main() {
	name := flag.String("name", "Relay", "adapter name")
	address := flag.String("address", "10.13.37.2/32", "this end of the tunnel (IPv4)")
	address6 := flag.String("address6", "", "optional dual-stack IPv6 address on the tunnel (e.g. fd13:37::2/128)")
	dns := flag.String("dns", "", "DNS server to set on the adapter; empty leaves it alone")
	dns6 := flag.String("dns6", "", "optional IPv6 DNS server to set on the adapter")
	routes := flag.String("routes", "0.0.0.0/0", "comma-separated prefixes to send through the tunnel")
	routes6 := flag.String("routes6", "", "comma-separated IPv6 prefixes to send through the tunnel (defaults to ::/0 when -address6 is set)")
	wgTcpBridge := flag.String("wg-tcp-bridge", "", "optional WireGuard-over-TCP loopback/ADB bridge endpoint (e.g. 127.0.0.1:51821)")
	emitStats := flag.Bool("emit-stats", false, "periodically emit STATS rx=<bytes> tx=<bytes> lines on the control channel")
	pipe := flag.String("config-pipe", "",
		"named pipe carrying the configuration and readiness; stdin/stdout when empty")
	blockLeaks := flag.Bool("block-leaks", true,
		"block traffic that would bypass the tunnel (non-tunnel adapters, other resolvers, IPv6, mDNS/LLMNR/NBNS)")
	strictLeakBlock := flag.Bool("strict-leak-block", true,
		"fail closed and refuse to bring up the tunnel if WFP leak protection cannot be installed")
	holdOnLoss := flag.Bool("hold-on-loss", false,
		"kill switch: when the phone stops answering, keep the tunnel's routes and filters active so nothing leaks")
	persistKillSwitch := flag.Bool("persist-killswitch", false,
		"with -hold-on-loss, persist WFP lockdown filters across process crashes until DISCONNECT or -clear-killswitch")
	clearKillSwitch := flag.Bool("clear-killswitch", false,
		"remove any persisted Relay kill-switch filters from Windows Filtering Platform and exit")
	allowLAN := flag.Bool("allow-lan", false,
		"exclusion: permit RFC1918 and link-local IPv4 LAN traffic outside the tunnel (DNS/mDNS/LLMNR stay blocked)")
	allowDiscovery := flag.Bool("allow-discovery", false,
		"exclusion: permit Relay UI UDP port 47654 discovery and roaming beacons on physical adapters")
	discoveryApp := flag.String("discovery-app", "",
		"optional path to Relay.App.exe used with -allow-discovery")
	excludeApps := flag.String("exclude-apps", "",
		"exclusion: comma-separated absolute .exe paths allowed to bypass the tunnel (non-DNS IPv4 only)")
	excludeCIDRs := flag.String("exclude-cidrs", "",
		"exclusion: comma-separated CIDR prefixes allowed to bypass the tunnel (non-DNS only)")
	flag.Parse()

	if *clearKillSwitch {
		if err := clearPersistentKillSwitch(); err != nil {
			fmt.Fprintf(os.Stderr, "relaywg-client: clear kill-switch failed: %v\n", err)
			os.Exit(1)
		}
		fmt.Fprintln(os.Stderr, "relaywg-client: persistent kill-switch filters cleared")
		return
	}

	parsedApps, err := parseAppExclusions(*excludeApps)
	if err != nil {
		fmt.Fprintf(os.Stderr, "relaywg-client: %v\n", err)
		os.Exit(1)
	}
	parsedCIDRs, err := parsePrefixExclusions(*excludeCIDRs)
	if err != nil {
		fmt.Fprintf(os.Stderr, "relaywg-client: %v\n", err)
		os.Exit(1)
	}
	var validatedBridge string
	if strings.TrimSpace(*wgTcpBridge) != "" {
		validatedBridge, err = validateWgTcpBridgeAddr(*wgTcpBridge)
		if err != nil {
			fmt.Fprintf(os.Stderr, "relaywg-client: %v\n", err)
			os.Exit(1)
		}
	}

	cfg := runConfig{
		name:              *name,
		address:           *address,
		address6:          *address6,
		dns:               *dns,
		dns6:              *dns6,
		routes:            *routes,
		routes6:           *routes6,
		wgTcpBridge:       validatedBridge,
		emitStats:         *emitStats,
		pipe:              *pipe,
		blockLeaks:        *blockLeaks,
		strictLeakBlock:   *strictLeakBlock,
		holdOnLoss:        *holdOnLoss,
		persistKillSwitch: *persistKillSwitch,
		allowLAN:          *allowLAN,
		allowDiscovery:    *allowDiscovery,
		discoveryApp:      *discoveryApp,
		excludeApps:       parsedApps,
		excludePrefixes:   parsedCIDRs,
	}

	if err := run(cfg); err != nil {
		fmt.Fprintf(os.Stderr, "relaywg-client: %v\n", err)
		os.Exit(1)
	}
}

func run(cfg runConfig) error {
	channel, err := openChannel(cfg.pipe)
	if err != nil {
		return err
	}
	defer channel.Close()

	reader := bufio.NewReader(channel)
	config, parentGone, err := readConfig(reader)
	if err != nil {
		return err
	}

	prefix, err := netip.ParsePrefix(cfg.address)
	if err != nil {
		return fmt.Errorf("address %q: %w", cfg.address, err)
	}
	parsedRoutes, err := parsePrefixes(cfg.routes)
	if err != nil {
		return err
	}
	var tunnelRoutes []netip.Prefix
	var routesFromRoutes6 []netip.Prefix
	for _, r := range parsedRoutes {
		if r.Addr().Is4() {
			tunnelRoutes = append(tunnelRoutes, r)
		} else {
			routesFromRoutes6 = append(routesFromRoutes6, r)
		}
	}
	if len(tunnelRoutes) == 0 {
		return errors.New("no IPv4 routes to install — the tunnel would carry nothing")
	}

	var prefix6 netip.Prefix
	var routes6 []netip.Prefix
	hasIPv6 := strings.TrimSpace(cfg.address6) != ""
	if hasIPv6 {
		prefix6, err = netip.ParsePrefix(strings.TrimSpace(cfg.address6))
		if err != nil {
			return fmt.Errorf("address6 %q: %w", cfg.address6, err)
		}
		r6 := strings.TrimSpace(cfg.routes6)
		if r6 != "" {
			parsed6, err := parsePrefixes(r6)
			if err != nil {
				return err
			}
			for _, r := range parsed6 {
				if r.Addr().Is6() {
					routes6 = append(routes6, r)
				}
			}
		} else if len(routesFromRoutes6) > 0 {
			routes6 = routesFromRoutes6
		}
		if len(routes6) == 0 {
			routes6 = []netip.Prefix{netip.MustParsePrefix("::/0")}
		}
	}

	var resolvers []netip.Addr
	if cfg.dns != "" {
		server, err := netip.ParseAddr(cfg.dns)
		if err != nil {
			return fmt.Errorf("dns %q: %w", cfg.dns, err)
		}
		resolvers = append(resolvers, server)
	}
	if cfg.dns6 != "" {
		server6, err := netip.ParseAddr(cfg.dns6)
		if err != nil {
			return fmt.Errorf("dns6 %q: %w", cfg.dns6, err)
		}
		resolvers = append(resolvers, server6)
	}

	var bridge *udpToTcpBridge
	if strings.TrimSpace(cfg.wgTcpBridge) != "" {
		b, err := startUdpToTcpBridge(strings.TrimSpace(cfg.wgTcpBridge))
		if err != nil {
			return err
		}
		bridge = b
		defer bridge.Close()
		config = replaceIpcEndpoint(config, bridge.LocalEndpoint())
		fmt.Fprintf(os.Stderr, "WireGuard-over-TCP bridge active via %s -> %s\n", bridge.LocalEndpoint(), cfg.wgTcpBridge)
	}

	adapter, err := tun.CreateTUN(cfg.name, mtu)
	if err != nil {
		return fmt.Errorf("could not create the %q adapter (Administrator required): %w", cfg.name, err)
	}
	defer adapter.Close()

	native, ok := adapter.(*tun.NativeTun)
	if !ok {
		return errors.New("the adapter is not a WinTun device")
	}
	luid := winipcfg.LUID(native.LUID())

	// Install blanket WFP leak protection IMMEDIATELY once the WinTun LUID exists,
	// before assigning IPs/routes or bringing WireGuard up, so no packet or DNS
	// query can race out physical NICs during interface bring-up.
	persistent := cfg.blockLeaks && cfg.holdOnLoss && cfg.persistKillSwitch
	var stopWfp func()
	disarmOnExit := true
	defer func() {
		if stopWfp != nil && (!persistent || disarmOnExit) {
			stopWfp()
		}
	}()

	if cfg.blockLeaks {
		stop, err := enableLeakProtection(leakOptions{
			tunLUID:         uint64(luid),
			resolvers:       resolvers,
			ipv6Tunnel:      hasIPv6,
			allowLAN:        cfg.allowLAN,
			allowDiscovery:  cfg.allowDiscovery,
			discoveryApp:    cfg.discoveryApp,
			excludeApps:     cfg.excludeApps,
			excludePrefixes: cfg.excludePrefixes,
			persistent:      persistent,
		})
		if err != nil {
			fmt.Fprintf(os.Stderr, "leak protection unavailable: %v\n", err)
			if _, werr := fmt.Fprintln(channel, leakProtectionFailedLine); werr != nil {
				return fmt.Errorf("reporting that leak protection is unavailable: %w", werr)
			}
			if cfg.strictLeakBlock {
				return fmt.Errorf("refusing to start without WFP leak protection: %w", err)
			}
		} else {
			stopWfp = stop
			if hasIPv6 {
				fmt.Fprintln(os.Stderr, "leak protection on: non-tunnel traffic blocked, DNS pinned to tunnel, off-tunnel IPv6 refused")
			} else {
				fmt.Fprintln(os.Stderr, "leak protection on: non-tunnel traffic blocked, DNS pinned to tunnel, IPv6 refused")
			}
		}
	}

	cleanupBypassRoutes := installBypassRoutes(uint64(luid), cfg.excludePrefixes, cfg.allowLAN)
	defer cleanupBypassRoutes()

	// Cut pre-existing foreign IPv4 TCP connections right away (before waiting
	// up to 20s for handshake) so long-lived flows don't keep leaking out the
	// physical NIC during bring-up. Existing UDP/QUIC and IPv6 sockets are
	// simultaneously blocked via BFE ALE reauthorization above.
	if closed := resetForeignConnections(prefix.Addr(), peerEndpointAddr(config), cfg.excludePrefixes, cfg.allowLAN); closed > 0 {
		fmt.Fprintf(os.Stderr, "closed %d connection(s) that predated the tunnel\n", closed)
	}

	dev := device.NewDevice(adapter, newResilientBind(),
		device.NewLogger(device.LogLevelError, "relaywg-client: "))
	defer dev.Close()

	if err := dev.IpcSet(config); err != nil {
		return fmt.Errorf("the configuration was rejected: %w", err)
	}
	if err := dev.Up(); err != nil {
		return fmt.Errorf("the tunnel did not come up: %w", err)
	}

	if err := configureAdapter(luid, prefix, cfg.dns, tunnelRoutes); err != nil {
		return err
	}
	if hasIPv6 {
		if err := configureAdapter6(luid, prefix6, cfg.dns6, routes6); err != nil {
			return err
		}
	}

	fmt.Fprintf(os.Stderr, "tunnel up on %s via %q\n", prefix, cfg.name)

	if !waitForHandshake(dev, handshakeTimeout) {
		fmt.Fprintln(os.Stderr, "no handshake: the peer never answered")
		io.WriteString(channel, noHandshakeLine+"\n")
		return errors.New("the peer never completed a handshake")
	}

	if _, err := io.WriteString(channel, "READY\n"); err != nil {
		return fmt.Errorf("reporting readiness: %w", err)
	}

	// Once READY is sent under persistent kill-switch mode, abnormal crashes or
	// unexpected pipe loss must leave persistent BFE lockdown filters intact
	// until an explicit DISCONNECT command or -clear-killswitch runs.
	if persistent {
		disarmOnExit = false
	}

	peerKey := peerPublicKey(config)

	var gone <-chan struct{}
	if cfg.holdOnLoss {
		go watchPeer(dev, handshakeStale, func(lost bool) {
			line := peerBackLine
			if lost {
				line = peerLostLine
			}
			fmt.Fprintf(os.Stderr, "peer %s\n", strings.ToLower(line))
			io.WriteString(channel, line+"\n")
		})
	} else {
		gone = peerLost(dev)
	}

	stopBg := make(chan struct{})
	defer close(stopBg)

	if cfg.emitStats {
		go emitTunnelStats(dev, channel, stopBg)
	}

	intentionalStop := waitForShutdown(reader, parentGone, gone, func(line string) {
		endpoint, ok := strings.CutPrefix(line, endpointPrefix)
		if !ok {
			return
		}
		endpoint = strings.TrimSpace(endpoint)
		if err := roam(dev, peerKey, endpoint); err != nil {
			fmt.Fprintf(os.Stderr, "could not follow the peer to %q: %v\n", endpoint, err)
			return
		}
		fmt.Fprintf(os.Stderr, "the peer moved to %s\n", endpoint)
	})

	if intentionalStop {
		disarmOnExit = true
	}

	fmt.Fprintln(os.Stderr, "tearing down")
	return nil
}

// roam re-points the tunnel at the peer's new address without rebuilding it.
func roam(dev *device.Device, peerKey, endpoint string) error {
	if peerKey == "" {
		return errors.New("the configuration named no peer to move")
	}
	if _, err := netip.ParseAddrPort(endpoint); err != nil {
		return fmt.Errorf("not an address and port: %w", err)
	}
	return dev.IpcSet(fmt.Sprintf(
		"public_key=%s\nupdate_only=true\nendpoint=%s\n", peerKey, endpoint))
}

// peerPublicKey returns the peer's key from an IPC configuration.
func peerPublicKey(config string) string {
	for _, line := range strings.Split(config, "\n") {
		if key, ok := strings.CutPrefix(strings.TrimSpace(line), "public_key="); ok {
			return key
		}
	}
	return ""
}

// peerEndpointAddr extracts the literal endpoint IP from an IPC configuration,
// returning an invalid Addr if none is present or malformed.
func peerEndpointAddr(config string) netip.Addr {
	for _, line := range strings.Split(config, "\n") {
		if ep, ok := strings.CutPrefix(strings.TrimSpace(line), "endpoint="); ok {
			if ap, err := netip.ParseAddrPort(strings.TrimSpace(ep)); err == nil {
				return ap.Addr()
			}
		}
	}
	return netip.Addr{}
}

// waitForHandshake reports whether the peer answered within [within].
func waitForHandshake(dev *device.Device, within time.Duration) bool {
	deadline := time.Now().Add(within)
	for {
		if lastHandshakeUnix(dev) > 0 {
			return true
		}
		if time.Now().After(deadline) {
			return false
		}
		time.Sleep(handshakePoll)
	}
}

// peerLost closes when the peer stops handshaking.
func peerLost(dev *device.Device) <-chan struct{} {
	lost := make(chan struct{})
	go func() {
		defer close(lost)
		for {
			time.Sleep(time.Second)
			if peerIsStale(lastHandshakeUnix(dev), time.Now(), handshakeStale) {
				fmt.Fprintln(os.Stderr, "the peer stopped handshaking; tearing down")
				return
			}
		}
	}()
	return lost
}

// watchPeer reports the peer going quiet and coming back, for the kill switch.
func watchPeer(dev *device.Device, stale time.Duration, report func(lost bool)) {
	lost := false
	for {
		time.Sleep(time.Second)
		if nowLost := peerIsStale(lastHandshakeUnix(dev), time.Now(), stale); nowLost != lost {
			lost = nowLost
			report(lost)
		}
	}
}

// peerIsStale is the rule [peerLost] and [watchPeer] share.
func peerIsStale(lastHandshake int64, now time.Time, stale time.Duration) bool {
	if lastHandshake == 0 {
		return false
	}
	return now.Sub(time.Unix(lastHandshake, 0)) > stale
}

func lastHandshakeUnix(dev *device.Device) int64 {
	status, err := dev.IpcGet()
	if err != nil {
		return 0
	}
	for _, line := range strings.Split(status, "\n") {
		value, ok := strings.CutPrefix(strings.TrimSpace(line), "last_handshake_time_sec=")
		if !ok {
			continue
		}
		seconds, err := strconv.ParseInt(value, 10, 64)
		if err != nil {
			return 0
		}
		return seconds
	}
	return 0
}

func configureAdapter(luid winipcfg.LUID, prefix netip.Prefix, dns string, routes []netip.Prefix) error {
	if err := luid.SetIPAddressesForFamily(windows.AF_INET, []netip.Prefix{prefix}); err != nil {
		return fmt.Errorf("setting %s on the adapter: %w", prefix, err)
	}

	iface, err := luid.IPInterface(windows.AF_INET)
	if err != nil {
		return fmt.Errorf("reading the adapter's IP settings: %w", err)
	}
	iface.NLMTU = mtu
	iface.UseAutomaticMetric = false
	iface.Metric = routeMetric
	iface.RouterDiscoveryBehavior = winipcfg.RouterDiscoveryDisabled
	iface.DadTransmits = 0
	if err := iface.Set(); err != nil {
		return fmt.Errorf("applying the adapter's IP settings: %w", err)
	}

	if dns != "" {
		server, err := netip.ParseAddr(dns)
		if err != nil {
			return fmt.Errorf("dns %q: %w", dns, err)
		}
		if err := luid.SetDNS(windows.AF_INET, []netip.Addr{server}, nil); err != nil {
			return fmt.Errorf("setting DNS: %w", err)
		}
	}

	data := make([]*winipcfg.RouteData, 0, len(routes))
	for _, route := range routes {
		data = append(data, &winipcfg.RouteData{
			Destination: route,
			NextHop:     netip.IPv4Unspecified(),
			Metric:      0,
		})
	}
	if err := luid.SetRoutesForFamily(windows.AF_INET, data); err != nil {
		return fmt.Errorf("setting routes: %w", err)
	}
	return nil
}

func configureAdapter6(luid winipcfg.LUID, prefix netip.Prefix, dns string, routes []netip.Prefix) error {
	if err := luid.SetIPAddressesForFamily(windows.AF_INET6, []netip.Prefix{prefix}); err != nil {
		return fmt.Errorf("setting IPv6 %s on the adapter: %w", prefix, err)
	}

	iface, err := luid.IPInterface(windows.AF_INET6)
	if err != nil {
		return fmt.Errorf("reading the adapter's IPv6 settings: %w", err)
	}
	iface.NLMTU = mtu
	iface.UseAutomaticMetric = false
	iface.Metric = routeMetric
	iface.RouterDiscoveryBehavior = winipcfg.RouterDiscoveryDisabled
	iface.DadTransmits = 0
	if err := iface.Set(); err != nil {
		return fmt.Errorf("applying the adapter's IPv6 settings: %w", err)
	}

	if dns != "" {
		server, err := netip.ParseAddr(dns)
		if err != nil {
			return fmt.Errorf("dns6 %q: %w", dns, err)
		}
		if err := luid.SetDNS(windows.AF_INET6, []netip.Addr{server}, nil); err != nil {
			return fmt.Errorf("setting IPv6 DNS: %w", err)
		}
	}

	data := make([]*winipcfg.RouteData, 0, len(routes))
	for _, route := range routes {
		data = append(data, &winipcfg.RouteData{
			Destination: route,
			NextHop:     netip.IPv6Unspecified(),
			Metric:      0,
		})
	}
	if err := luid.SetRoutesForFamily(windows.AF_INET6, data); err != nil {
		return fmt.Errorf("setting IPv6 routes: %w", err)
	}
	return nil
}

func emitTunnelStats(dev *device.Device, channel io.Writer, done <-chan struct{}) {
	ticker := time.NewTicker(time.Second)
	defer ticker.Stop()
	for {
		select {
		case <-done:
			return
		case <-ticker.C:
			status, err := dev.IpcGet()
			if err != nil {
				return
			}
			var rx, tx int64
			for _, line := range strings.Split(status, "\n") {
				if v, ok := strings.CutPrefix(strings.TrimSpace(line), "rx_bytes="); ok {
					if n, err := strconv.ParseInt(v, 10, 64); err == nil {
						rx += n
					}
				} else if v, ok := strings.CutPrefix(strings.TrimSpace(line), "tx_bytes="); ok {
					if n, err := strconv.ParseInt(v, 10, 64); err == nil {
						tx += n
					}
				}
			}
			if _, err := fmt.Fprintf(channel, "STATS rx=%d tx=%d\n", rx, tx); err != nil {
				return
			}
		}
	}
}

func parsePrefixes(list string) ([]netip.Prefix, error) {
	parsed := []netip.Prefix{}
	for _, item := range strings.Split(list, ",") {
		item = strings.TrimSpace(item)
		if item == "" {
			continue
		}
		prefix, err := netip.ParsePrefix(item)
		if err != nil {
			return nil, fmt.Errorf("route %q: %w", item, err)
		}
		parsed = append(parsed, prefix)
	}
	if len(parsed) == 0 {
		return nil, errors.New("no routes to install — the tunnel would carry nothing")
	}
	return parsed, nil
}

func openChannel(pipe string) (io.ReadWriteCloser, error) {
	if pipe == "" {
		return stdioChannel{}, nil
	}
	path := `\\.\pipe\` + pipe
	handle, err := os.OpenFile(path, os.O_RDWR, 0)
	if err != nil {
		return nil, fmt.Errorf("opening %s: %w", path, err)
	}
	return handle, nil
}

type stdioChannel struct{}

func (stdioChannel) Read(p []byte) (int, error)  { return os.Stdin.Read(p) }
func (stdioChannel) Write(p []byte) (int, error) { return os.Stdout.Write(p) }
func (stdioChannel) Close() error                { return nil }

func readConfig(stdin *bufio.Reader) (config string, parentGone bool, err error) {
	var builder strings.Builder
	for {
		line, readErr := stdin.ReadString('\n')
		trimmed := strings.TrimRight(line, "\r\n")
		if trimmed == configTerminator {
			break
		}
		if trimmed != "" {
			builder.WriteString(trimmed)
			builder.WriteByte('\n')
		}
		if readErr != nil {
			if !errors.Is(readErr, io.EOF) {
				return "", true, fmt.Errorf("reading the configuration: %w", readErr)
			}
			parentGone = true
			break
		}
	}
	if builder.Len() == 0 {
		return "", parentGone, errors.New("no configuration on stdin")
	}
	return builder.String(), parentGone, nil
}

// waitForShutdown returns true if the shutdown was requested intentionally
// (explicit DISCONNECT command, console interrupt, or normal non-hold peer loss),
// and false if the parent pipe broke unexpectedly (crash/kill).
func waitForShutdown(
	channel *bufio.Reader,
	parentAlreadyGone bool,
	peerGone <-chan struct{},
	onLine func(string),
) bool {
	signals := make(chan os.Signal, 1)
	signal.Notify(signals, os.Interrupt, syscall.SIGTERM)

	if parentAlreadyGone {
		select {
		case <-signals:
			return true
		case <-peerGone:
			return true
		}
	}

	closed := make(chan struct{})
	disconnected := make(chan struct{})
	go func() {
		defer close(closed)
		scanner := bufio.NewScanner(channel)
		for scanner.Scan() {
			line := strings.TrimSpace(scanner.Text())
			if line == disconnectCommand {
				close(disconnected)
				return
			}
			onLine(line)
		}
	}()

	select {
	case <-signals:
		return true
	case <-disconnected:
		return true
	case <-peerGone:
		return true
	case <-closed:
		return false
	}
}
