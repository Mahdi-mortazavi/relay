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
//	in      the wireguard-go IPC configuration, ended by "END-CONFIG"
//	out     "READY" once traffic can flow, then nothing
//	stderr  human-readable progress and errors
//	exit    tear the tunnel down when the stream closes, or on Ctrl+Break
//
// The stream stays open for the life of the tunnel, which is what makes its
// closing mean "the app is gone" -- including the app crashing, the case that
// matters, because a tunnel nobody is watching still holds the machine's
// routes. That is also why the configuration ends with a sentinel rather than
// with EOF: reading to EOF would consume the very signal being waited on.
//
// The adapter disappears when this process exits, by any route including being
// killed: WinTun removes it with the last handle. That is what makes the
// teardown safe rather than merely careful -- the routes go with it, so there
// is no state left behind for a crash to strand.
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
	"strings"
	"syscall"

	"golang.org/x/sys/windows"
	"golang.zx2c4.com/wireguard/conn"
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
)

func main() {
	name := flag.String("name", "Relay", "adapter name")
	address := flag.String("address", "10.13.37.2/32", "this end of the tunnel")
	dns := flag.String("dns", "", "DNS server to set on the adapter; empty leaves it alone")
	routes := flag.String("routes", "0.0.0.0/0", "comma-separated prefixes to send through the tunnel")
	pipe := flag.String("config-pipe", "",
		"named pipe carrying the configuration and readiness; stdin/stdout when empty")
	flag.Parse()

	if err := run(*name, *address, *dns, *routes, *pipe); err != nil {
		fmt.Fprintf(os.Stderr, "relaywg-client: %v\n", err)
		os.Exit(1)
	}
}

func run(name, address, dns, routes, pipe string) error {
	// The app talks over a named pipe rather than stdin, because a process
	// launched through the elevation prompt cannot have its streams redirected
	// at all -- and the only other way to hand it a private key would be a temp
	// file, which is exactly what should not exist. Stdin remains for running
	// this by hand, and for the tests.
	channel, err := openChannel(pipe)
	if err != nil {
		return err
	}
	defer channel.Close()

	reader := bufio.NewReader(channel)
	config, parentGone, err := readConfig(reader)
	if err != nil {
		return err
	}

	prefix, err := netip.ParsePrefix(address)
	if err != nil {
		return fmt.Errorf("address %q: %w", address, err)
	}
	tunnelRoutes, err := parsePrefixes(routes)
	if err != nil {
		return err
	}

	// Creating the adapter is the step that needs Administrator. Its failure is
	// the one a person is most likely to hit, so it is reported on its own
	// rather than folded into a generic "could not start".
	adapter, err := tun.CreateTUN(name, mtu)
	if err != nil {
		return fmt.Errorf("could not create the %q adapter (Administrator required): %w", name, err)
	}
	defer adapter.Close()

	native, ok := adapter.(*tun.NativeTun)
	if !ok {
		return errors.New("the adapter is not a WinTun device")
	}
	luid := winipcfg.LUID(native.LUID())

	dev := device.NewDevice(adapter, conn.NewDefaultBind(),
		device.NewLogger(device.LogLevelError, "relaywg-client: "))
	defer dev.Close()

	if err := dev.IpcSet(config); err != nil {
		return fmt.Errorf("the configuration was rejected: %w", err)
	}
	if err := dev.Up(); err != nil {
		return fmt.Errorf("the tunnel did not come up: %w", err)
	}

	if err := configureAdapter(luid, prefix, dns, tunnelRoutes); err != nil {
		return err
	}

	fmt.Fprintf(os.Stderr, "tunnel up on %s via %q\n", prefix, name)
	if _, err := io.WriteString(channel, "READY\n"); err != nil {
		return fmt.Errorf("reporting readiness: %w", err)
	}

	waitForShutdown(reader, parentGone)
	fmt.Fprintln(os.Stderr, "tearing down")
	// The deferred Close calls remove the adapter, and Windows drops its
	// addresses and routes with it.
	return nil
}

// configureAdapter gives the tunnel its address, DNS and routes.
//
// Order matters: the address has to exist before a route can point at the
// interface, and the interface metric has to be set before the routes or
// Windows may prefer the physical adapter for the same prefix.
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
	// Without this Windows sends router solicitations out of a point-to-point
	// tunnel that has no router, which shows up as a delay before traffic flows.
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

	// Deliberately additive. Nothing here deletes or rewrites an existing route:
	// the tunnel's default route wins on metric while it exists and disappears
	// with the adapter, so a crash cannot strand a machine with a route to
	// somewhere that is gone. The phone itself stays reachable because it sits
	// on the local subnet, whose on-link route is more specific than 0.0.0.0/0
	// and stays on the physical adapter -- which is also what stops the tunnel's
	// own UDP from being routed into itself.
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

// openChannel returns the stream the configuration arrives on and readiness is
// reported over: a named pipe when one is given, otherwise stdin and stdout.
//
// The pipe is opened read-write and stays open for the life of the tunnel. Its
// closing is how this process learns the app has gone -- including the app
// crashing, which is the case that matters, because a tunnel nobody is watching
// still holds the machine's routes.
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

// stdioChannel reads from stdin and writes to stdout as one stream.
type stdioChannel struct{}

func (stdioChannel) Read(p []byte) (int, error)  { return os.Stdin.Read(p) }
func (stdioChannel) Write(p []byte) (int, error) { return os.Stdout.Write(p) }
func (stdioChannel) Close() error                { return nil }

// readConfig reads the IPC configuration up to [configTerminator], and reports
// whether stdin reached EOF while doing so.
//
// The terminator exists because stdin is two things at once: the way the
// configuration arrives, and the way this process learns its parent is gone.
// Reading to EOF for the first would consume the second, and the tunnel would
// tear itself down the instant it came up.
//
// Reaching EOF instead of the terminator is not an error — that is what
// `type relay.conf | relaywg-client` looks like, and it is a reasonable way to
// run this by hand. It only means the parent is already gone, so Ctrl+Break
// becomes the only way out.
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

// waitForShutdown returns when the parent goes away or the console interrupts.
//
// Both matter. The parent closing stdin is the ordinary Disconnect; the signal
// is what a person pressing Ctrl+C in a console window sends. Waiting on only
// one of them leaves a tunnel running with nobody watching it.
func waitForShutdown(channel *bufio.Reader, parentAlreadyGone bool) {
	signals := make(chan os.Signal, 1)
	signal.Notify(signals, os.Interrupt, syscall.SIGTERM)

	if parentAlreadyGone {
		<-signals
		return
	}

	closed := make(chan struct{})
	go func() {
		io.Copy(io.Discard, channel)
		close(closed)
	}()

	select {
	case <-signals:
	case <-closed:
	}
}
