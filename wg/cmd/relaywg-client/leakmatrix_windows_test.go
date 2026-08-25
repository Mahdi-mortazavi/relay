package main

import (
	"bufio"
	"errors"
	"fmt"
	"net"
	"os"
	"os/exec"
	"strings"
	"testing"
	"time"
)

// What leak protection actually blocks, measured rather than reasoned about.
//
// The rules are four lines of Go and it is easy to convince yourself you know
// what they do. The loopback bug is what that costs: "block all of
// ALE_AUTH_CONNECT_V6" was read as "block IPv6 leaving the machine" and it
// meant "block IPv6", localhost included. Nobody noticed until it was measured.
//
// So this measures. Every probe runs twice -- once with no tunnel, once with
// the filters live -- and the assertion is on the *transition*. That is what
// isolates the filter's effect from whatever this machine could reach anyway,
// which a single after-the-fact probe cannot do: a runner with no IPv6 route
// looks exactly like a runner whose IPv6 has been blocked.
//
// The contract being pinned, in both directions:
//
//	must keep working   loopback v4 and v6, ordinary IPv4, DNS to the tunnel's
//	                    own resolver
//	must stop working   DNS to any other resolver, IPv6 off the machine
//
// The first half matters as much as the second. A rule that blocks more than it
// needs to is not "extra safe" -- it is unrelated software breaking, and the
// user turning the whole feature off to get their machine back.
func TestLeakProtectionBlocksWhatItShouldAndNothingElse(t *testing.T) {
	if os.Getenv("RELAYWG_CLIENT") == "" {
		t.Skip("RELAYWG_CLIENT is not set; CI builds the client and points this at it")
	}

	// The resolver the tunnel is told to use, and therefore the one address on
	// port 53 that must survive.
	const tunnelResolver = "1.1.1.1"
	// Any other resolver. This is the leak: on a shared Wi-Fi it is the router,
	// answering alongside the tunnel.
	const otherResolver = "9.9.9.9"

	// Listeners of our own, so "this still works" is a real connection rather
	// than an assumption about something on the internet.
	lan := listenOrSkip(t, "tcp4", nonLoopbackAddr(t)+":0")
	loopback4 := listenOrSkip(t, "tcp4", "127.0.0.1:0")
	loopback6 := listenOrSkip(t, "tcp6", "[::1]:0")

	probes := []struct {
		what        string
		run         func() error
		mustSurvive bool // true: must still work; false: must be blocked
	}{
		{"loopback IPv4", func() error { return dialTCP(loopback4) }, true},
		{"loopback IPv6", func() error { return dialTCP(loopback6) }, true},
		{"ordinary IPv4", func() error { return dialTCP(lan) }, true},
		{"DNS to the tunnel's resolver", func() error { return sendUDP(tunnelResolver + ":53") }, true},
		{"DNS to another resolver", func() error { return sendUDP(otherResolver + ":53") }, false},
		{"IPv6 off the machine", func() error { return sendUDP("[2606:4700:4700::1111]:53") }, false},
	}

	before := make([]error, len(probes))
	for i, probe := range probes {
		before[i] = probe.run()
		t.Logf("baseline   %-30s %s", probe.what, outcome(before[i]))
	}

	stop := startProtectedTunnel(t, "RelayTestMatrix", tunnelResolver)
	defer stop()

	for i, probe := range probes {
		after := probe.run()
		t.Logf("protected  %-30s %s", probe.what, outcome(after))

		if before[i] != nil {
			// It did not work without the tunnel either, so this machine can say
			// nothing about it. Logged rather than passed over in silence: a
			// green run that skipped its own assertions has proven nothing.
			t.Logf("           %-30s NOT MEASURED: unreachable at baseline", probe.what)
			continue
		}

		switch {
		case probe.mustSurvive && after != nil:
			t.Errorf("%s worked before leak protection and not after (%v).\n"+
				"A rule is blocking more than it needs to, which breaks unrelated "+
				"software and makes turning leak protection off look like the fix.",
				probe.what, after)
		case !probe.mustSurvive && after == nil:
			t.Errorf("%s still worked with leak protection on. "+
				"This is the leak the feature exists to close.", probe.what)
		}
	}
}

// startProtectedTunnel brings the client up far enough that its filters are
// live, and returns a function that tears it down.
//
// No peer answers, and none is needed: the filters go in before the handshake
// is waited for, so they are live for the whole handshake timeout.
func startProtectedTunnel(t *testing.T, adapter, resolver string) func() {
	t.Helper()

	_, serverPublic := keyPair(t)
	clientPrivate, _ := keyPair(t)

	client := exec.Command(os.Getenv("RELAYWG_CLIENT"),
		"-name", adapter,
		"-address", "10.13.37.2/32",
		// A documentation prefix, so this never takes over the runner's default
		// route: what is under test is the filters, not the routing table.
		"-routes", "198.51.100.0/24",
		"-dns", resolver,
	)
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

// sendUDP reports whether the machine was allowed to send at all.
//
// Whether an answer comes back is the network's business; what a WFP block at
// ALE_AUTH_CONNECT changes is whether the send is permitted, and that surfaces
// as an error on the connect or on the first write.
func sendUDP(address string) error {
	conn, err := net.DialTimeout("udp", address, 4*time.Second)
	if err != nil {
		return err
	}
	defer conn.Close()
	_ = conn.SetWriteDeadline(time.Now().Add(4 * time.Second))
	// A well-formed A query for example.com, so nothing downstream is being
	// asked to make sense of garbage.
	query := []byte{
		0x12, 0x34, 0x01, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
		0x07, 0x65, 0x78, 0x61, 0x6d, 0x70, 0x6c, 0x65,
		0x03, 0x63, 0x6f, 0x6d, 0x00, 0x00, 0x01, 0x00, 0x01,
	}
	_, err = conn.Write(query)
	return err
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

// nonLoopbackAddr is this machine's address as the routing table sees it.
func nonLoopbackAddr(t *testing.T) string {
	t.Helper()
	conn, err := net.Dial("udp4", "192.0.2.1:9")
	if err != nil {
		t.Skipf("no routable IPv4 address: %v", err)
	}
	defer conn.Close()
	return conn.LocalAddr().(*net.UDPAddr).IP.String()
}
