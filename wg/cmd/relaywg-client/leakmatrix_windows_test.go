package main

import (
	"bufio"
	"errors"
	"fmt"
	"io"
	"net"
	"os"
	"os/exec"
	"sort"
	"strings"
	"testing"
	"time"
)

// What leak protection actually blocks, measured rather than reasoned about.
//
// Every probe runs twice -- once without filters, once with the filters live --
// and the assertion is on the transition so unreachable baseline targets report
// NOT MEASURED rather than false greens.
//
// With blanket non-tunnel blocking and LUID-pinned DNS active:
//
//	must keep working   loopback v4 and v6, link-local to self
//	must stop working   DNS to other resolvers, unpinned DNS to 1.1.1.1 over the
//	                    physical NIC, non-53 UDP/TCP outside the tunnel (including
//	                    DoH/443 and WireGuard ports), mDNS/LLMNR, and IPv6 off-host
func TestLeakProtectionBlocksWhatItShouldAndNothingElse(t *testing.T) {
	if os.Getenv("RELAYWG_CLIENT") == "" {
		t.Skip("RELAYWG_CLIENT is not set; CI builds the client and points this at it")
	}

	const tunnelResolver = "1.1.1.1"
	const otherResolver = "9.9.9.9"
	const externalProbe = "1.1.1.1"

	lan := listenOrSkip(t, "tcp4", nonLoopbackAddr(t)+":0")
	loopback4 := listenOrSkip(t, "tcp4", "127.0.0.1:0")
	loopback6 := listenOrSkip(t, "tcp6", "[::1]:0")
	linkLocal := linkLocalListener(t)

	probes := []struct {
		what        string
		run         func() error
		mustSurvive bool
	}{
		{"loopback IPv4", func() error { return dialTCP(loopback4) }, true},
		{"loopback IPv6", func() error { return dialTCP(loopback6) }, true},
		// Non-tunnel physical IPv4 (no longer left open around the tunnel).
		{"non-tunnel physical IPv4", func() error { return dialTCP(lan) }, false},
		// Off-tunnel query to 1.1.1.1:53 MUST fail when not routed via WinTun LUID.
		{"unpinned DNS via physical NIC (UDP)", func() error { return resolve(tunnelResolver + ":53") }, false},
		{"DNS to another resolver (UDP)", func() error { return resolve(otherResolver + ":53") }, false},
		{"DNS to another resolver (TCP)", func() error { return dialTCP(otherResolver + ":53") }, false},
		{"non-tunnel UDP transport (51820)", func() error { return sendUDP(externalProbe + ":51820") }, false},
		{"non-tunnel DoH/TCP transport (443)", func() error { return dialTCP(externalProbe + ":443") }, false},
		{"off-host mDNS (5353)", func() error { return sendUDP("224.0.0.251:5353") }, false},
		{"IPv6 off the machine", func() error { return sendUDP("[2606:4700:4700::1111]:53") }, false},
		// WFP classifies traffic to local link-local addresses as loopback.
		{"IPv6 to this machine", func() error { return dialTCP(linkLocal) }, true},
	}

	before := make([]error, len(probes))
	for i, probe := range probes {
		before[i] = probe.run()
		t.Logf("baseline   %-36s %s", probe.what, outcome(before[i]))
	}

	stop := startProtectedTunnel(t, "RelayTestMatrix", tunnelResolver)
	defer stop()

	for i, probe := range probes {
		after := probe.run()
		t.Logf("protected  %-36s %s", probe.what, outcome(after))

		if before[i] != nil {
			t.Logf("           %-36s NOT MEASURED: unreachable at baseline", probe.what)
			continue
		}

		switch {
		case probe.mustSurvive && after != nil:
			t.Errorf("%s worked before leak protection and not after (%v).", probe.what, after)
		case !probe.mustSurvive && after == nil:
			t.Errorf("%s bypassed leak protection and still succeeded.", probe.what)
		}
	}
}

func TestLeakProtectionExclusionAllowsCIDRButStillBlocksDNS(t *testing.T) {
	if os.Getenv("RELAYWG_CLIENT") == "" {
		t.Skip("RELAYWG_CLIENT is not set; CI builds the client and points this at it")
	}

	lanIP := nonLoopbackAddr(t)
	lan := listenOrSkip(t, "tcp4", lanIP+":0")

	stop := startProtectedTunnel(t, "RelayTestExcl", "1.1.1.1", "-exclude-cidrs", lanIP+"/32")
	defer stop()

	if err := dialTCP(lan); err != nil {
		t.Errorf("excluded CIDR %s/32 failed to connect: %v", lanIP, err)
	}
	if err := sendUDP(lanIP + ":53"); err == nil {
		t.Errorf("excluded CIDR %s/32 was permitted to send port 53 DNS outside tunnel", lanIP)
	}
}

// startProtectedTunnel brings the client up far enough that its filters are
// live, and returns a function that tears it down.
func startProtectedTunnel(t *testing.T, adapter, resolver string, extraArgs ...string) func() {
	t.Helper()

	_, serverPublic := keyPair(t)
	clientPrivate, _ := keyPair(t)

	args := []string{
		"-name", adapter,
		"-address", "10.13.37.2/32",
		"-routes", "198.51.100.0/24",
		"-dns", resolver,
	}
	args = append(args, extraArgs...)

	client := exec.Command(os.Getenv("RELAYWG_CLIENT"), args...)
	stdin, err := client.StdinPipe()
	if err != nil {
		t.Fatalf("stdin: %v", err)
	}
	stderr, err := client.StderrPipe()
	if err != nil {
		t.Fatalf("stderr: %v", err)
	}
	if err := client.Start(); err != nil {
		t.Fatalf("starting the client (Administrator required): %v", err)
	}
	stop := func() {
		_ = client.Process.Kill()
		_, _ = client.Process.Wait()
	}

	fmt.Fprintf(stdin,
		"private_key=%s\npublic_key=%s\nendpoint=127.0.0.1:9\nallowed_ip=0.0.0.0/0\n%s\n",
		clientPrivate, serverPublic, configTerminator)

	installed := make(chan bool, 1)
	go func() {
		scanner := bufio.NewScanner(stderr)
		for scanner.Scan() {
			line := scanner.Text()
			if strings.Contains(line, "leak protection on") {
				installed <- true
				return
			}
			if strings.Contains(line, "leak protection unavailable") {
				installed <- false
				return
			}
		}
		installed <- false
	}()

	select {
	case on := <-installed:
		if !on {
			stop()
			t.Skip("leak protection did not install here; nothing to measure")
		}
	case <-time.After(30 * time.Second):
		stop()
		t.Fatal("the client never said whether leak protection was installed")
	}
	return stop
}

func linkLocalListener(t *testing.T) string {
	t.Helper()

	interfaces, err := net.Interfaces()
	if err != nil {
		t.Skipf("cannot enumerate interfaces: %v", err)
	}
	for _, iface := range interfaces {
		if iface.Flags&net.FlagUp == 0 || iface.Flags&net.FlagLoopback != 0 {
			continue
		}
		addresses, err := iface.Addrs()
		if err != nil {
			continue
		}
		for _, address := range addresses {
			ipNet, ok := address.(*net.IPNet)
			if !ok || ipNet.IP.To4() != nil || !ipNet.IP.IsLinkLocalUnicast() {
				continue
			}
			host := fmt.Sprintf("[%s%%%s]:0", ipNet.IP, iface.Name)
			listener, err := net.Listen("tcp6", host)
			if err != nil {
				continue
			}
			t.Cleanup(func() { listener.Close() })
			go func() {
				for {
					conn, err := listener.Accept()
					if err != nil {
						return
					}
					conn.Close()
				}
			}()
			return listener.Addr().String()
		}
	}
	t.Skip("this machine has no usable link-local IPv6 address")
	return ""
}

func listenOrSkip(t *testing.T, network, address string) string {
	t.Helper()
	listener, err := net.Listen(network, address)
	if err != nil {
		t.Skipf("cannot listen on %s %s here: %v", network, address, err)
	}
	t.Cleanup(func() { listener.Close() })
	go func() {
		for {
			conn, err := listener.Accept()
			if err != nil {
				return
			}
			conn.Close()
		}
	}()
	return listener.Addr().String()
}

func dialTCP(address string) error {
	conn, err := net.DialTimeout("tcp", address, 4*time.Second)
	if err != nil {
		return err
	}
	return conn.Close()
}

func resolve(address string) error {
	conn, err := net.DialTimeout("udp", address, 4*time.Second)
	if err != nil {
		return err
	}
	defer conn.Close()

	_ = conn.SetDeadline(time.Now().Add(5 * time.Second))
	if _, err := conn.Write(dnsQuery); err != nil {
		return err
	}
	reply := make([]byte, 512)
	n, err := conn.Read(reply)
	if err != nil {
		return err
	}
	if n < 12 || reply[0] != dnsQuery[0] || reply[1] != dnsQuery[1] {
		return errors.New("no matching answer")
	}
	return nil
}

func sendUDP(address string) error {
	conn, err := net.DialTimeout("udp", address, 4*time.Second)
	if err != nil {
		return err
	}
	defer conn.Close()
	_ = conn.SetWriteDeadline(time.Now().Add(4 * time.Second))
	_, err = conn.Write(dnsQuery)
	return err
}

var dnsQuery = []byte{
	0x12, 0x34, 0x01, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
	0x07, 0x65, 0x78, 0x61, 0x6d, 0x70, 0x6c, 0x65,
	0x03, 0x63, 0x6f, 0x6d, 0x00, 0x00, 0x01, 0x00, 0x01,
}

func outcome(err error) string {
	if err == nil {
		return "allowed"
	}
	var netErr net.Error
	if errors.As(err, &netErr) && netErr.Timeout() {
		return "timed out (not a filter)"
	}
	return "refused: " + err.Error()
}

func nonLoopbackAddr(t *testing.T) string {
	t.Helper()
	conn, err := net.Dial("udp4", "192.0.2.1:9")
	if err != nil {
		t.Skipf("no routable IPv4 address: %v", err)
	}
	defer conn.Close()
	return conn.LocalAddr().(*net.UDPAddr).IP.String()
}

func TestWhatLeakProtectionCosts(t *testing.T) {
	if os.Getenv("RELAYWG_CLIENT") == "" {
		t.Skip("RELAYWG_CLIENT is not set; CI builds the client and points this at it")
	}

	host := nonLoopbackAddr(t)
	destination := echoListener(t, host)

	connectBefore := medianConnectTime(t, destination)
	throughputBefore := localThroughput(t, destination)
	t.Logf("leak protection OFF  connect %v   throughput %.0f MB/s",
		connectBefore, throughputBefore)

	// Explicitly exclude the test echo host IP so WFP evaluates the filter chain
	// and permits the connection without changing non-excluded block semantics.
	stop := startProtectedTunnel(t, "RelayTestCost", "1.1.1.1", "-exclude-cidrs", host+"/32")
	defer stop()

	connectAfter := medianConnectTime(t, destination)
	throughputAfter := localThroughput(t, destination)
	t.Logf("leak protection ON   connect %v   throughput %.0f MB/s",
		connectAfter, throughputAfter)

	if connectBefore > 0 && connectAfter > 8*connectBefore {
		t.Errorf("opening a connection went from %v to %v with leak protection on.",
			connectBefore, connectAfter)
	}

	if throughputBefore > 0 && throughputAfter < throughputBefore/3 {
		t.Errorf("throughput on an established connection went from %.0f to %.0f MB/s with leak protection on.",
			throughputBefore, throughputAfter)
	}
}

func echoListener(t *testing.T, host string) string {
	t.Helper()

	listener, err := net.Listen("tcp4", host+":0")
	if err != nil {
		t.Skipf("no routable interface to measure against: %v", err)
	}
	t.Cleanup(func() { listener.Close() })

	go func() {
		for {
			conn, err := listener.Accept()
			if err != nil {
				return
			}
			go func() {
				defer conn.Close()
				_, _ = io.Copy(conn, conn)
			}()
		}
	}()
	return listener.Addr().String()
}

func medianConnectTime(t *testing.T, address string) time.Duration {
	t.Helper()

	const samples = 25
	times := make([]time.Duration, 0, samples)
	for i := 0; i < samples; i++ {
		start := time.Now()
		conn, err := net.DialTimeout("tcp", address, 5*time.Second)
		if err != nil {
			t.Fatalf("connect %d failed: %v", i, err)
		}
		times = append(times, time.Since(start))
		conn.Close()
	}
	sort.Slice(times, func(a, b int) bool { return times[a] < times[b] })
	return times[len(times)/2]
}

func localThroughput(t *testing.T, address string) float64 {
	t.Helper()

	const size = 8 << 20

	conn, err := net.DialTimeout("tcp", address, 5*time.Second)
	if err != nil {
		t.Fatalf("throughput connection: %v", err)
	}
	defer conn.Close()
	_ = conn.SetDeadline(time.Now().Add(60 * time.Second))

	payload := make([]byte, 64<<10)
	start := time.Now()

	done := make(chan error, 1)
	go func() {
		_, err := io.CopyN(io.Discard, conn, size)
		done <- err
	}()
	for sent := 0; sent < size; sent += len(payload) {
		if _, err := conn.Write(payload); err != nil {
			t.Fatalf("throughput write: %v", err)
		}
	}
	if err := <-done; err != nil {
		t.Fatalf("throughput read: %v", err)
	}

	elapsed := time.Since(start).Seconds()
	if elapsed <= 0 {
		return 0
	}
	return float64(size) / elapsed / (1 << 20)
}
