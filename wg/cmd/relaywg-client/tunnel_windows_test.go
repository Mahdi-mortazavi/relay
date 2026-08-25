package main

import (
	"bufio"
	"crypto/rand"
	"encoding/hex"
	"fmt"
	"io"
	"net"
	"os"
	"os/exec"
	"strings"
	"testing"
	"time"

	"golang.org/x/crypto/curve25519"

	relaywg "github.com/Mahdi-mortazavi/relay/wg"
)

// The Windows half of Full Mode, against the real Windows networking stack.
//
// What this can prove, and what it deliberately does not try to:
//
// The endpoint's own suite already puts real TCP and UDP through a real tunnel,
// in a process and on a device. What none of that touches is the part of the
// Windows client that can only fail on Windows: creating a WinTun adapter,
// which needs Administrator; giving it an address, a metric and routes; and
// tearing all of it down again without stranding the machine.
//
// A full "browse the web through the tunnel" test is not possible on one
// machine, and pretending otherwise would produce a test that passes for the
// wrong reason. Both ends share a single routing table, so any destination
// routed into the tunnel is also routed into the tunnel when the endpoint tries
// to forward it onward -- traffic would loop rather than reach anything. So
// this proves the two things that are genuinely Windows-specific and genuinely
// untested elsewhere:
//
//  1. the adapter comes up, and WireGuard completes a handshake across it;
//  2. Windows really routes the prefixes it is given into that adapter --
//     measured at the endpoint, which decrypts what arrives.
//
// Needs Administrator. On a GitHub runner that is the default; on a desk it is
// not, and the test says so rather than failing obscurely.
func TestTheAdapterComesUpAndCarriesRoutedTraffic(t *testing.T) {
	binary := os.Getenv("RELAYWG_CLIENT")
	if binary == "" {
		t.Skip("RELAYWG_CLIENT is not set; CI builds the client and points this at it")
	}

	serverPrivate, serverPublic := keyPair(t)
	clientPrivate, clientPublic := keyPair(t)
	port := freeUDPPort(t)

	// The phone, in this process.
	endpoint, err := relaywg.Start(fmt.Sprintf(
		"private_key=%s\nlisten_port=%d\npublic_key=%s\nallowed_ip=10.13.37.2/32\n",
		serverPrivate, port, clientPublic))
	if err != nil {
		t.Fatalf("the endpoint did not start: %v", err)
	}
	defer endpoint.Stop()

	// The laptop. Routes a documentation prefix rather than 0.0.0.0/0: taking
	// over the default route on the machine running the test would cut the
	// runner off from GitHub, and the claim under test is "Windows routes what
	// it is told into this adapter", which a /24 shows exactly as well.
	client := exec.Command(binary,
		"-name", "RelayTest",
		"-address", "10.13.37.2/32",
		"-routes", "198.51.100.0/24",
	)
	stdin, err := client.StdinPipe()
	if err != nil {
		t.Fatalf("stdin: %v", err)
	}
	stdout, err := client.StdoutPipe()
	if err != nil {
		t.Fatalf("stdout: %v", err)
	}
	client.Stderr = os.Stderr
	if err := client.Start(); err != nil {
		t.Fatalf("starting the client: %v", err)
	}
	defer func() {
		stdin.Close()
		client.Wait()
	}()

	fmt.Fprintf(stdin, "private_key=%s\npublic_key=%s\nendpoint=127.0.0.1:%d\n"+
		"allowed_ip=0.0.0.0/0\npersistent_keepalive_interval=1\n%s\n",
		clientPrivate, serverPublic, port, configTerminator)

	waitForReady(t, stdout, client)

	// The handshake. Nothing else in the suite proves that the configuration
	// this client assembles is one the endpoint accepts across a real adapter.
	deadline := time.Now().Add(30 * time.Second)
	for endpoint.LastHandshakeUnix() == 0 {
		if time.Now().After(deadline) {
			t.Fatal("no WireGuard handshake within 30s of the adapter coming up")
		}
		time.Sleep(200 * time.Millisecond)
	}

	// And the routing. A connection to the routed prefix cannot complete -- the
	// endpoint would have to forward it back to itself -- but that is not what
	// is being measured. What is measured is that the packets left through the
	// adapter and the endpoint decrypted them, which can only happen if Windows
	// really picked this interface for that prefix.
	before := endpoint.BytesReceived()
	go func() {
		conn, err := net.DialTimeout("tcp", "198.51.100.5:80", 5*time.Second)
		if err == nil {
			conn.Close()
		}
	}()

	deadline = time.Now().Add(20 * time.Second)
	for endpoint.BytesReceived() <= before {
		if time.Now().After(deadline) {
			t.Fatalf("nothing arrived at the endpoint: Windows did not route "+
				"198.51.100.0/24 into the adapter (rx stuck at %d)", before)
		}
		time.Sleep(200 * time.Millisecond)
	}

	// Teardown: closing stdin is what the app does on Disconnect, and the
	// adapter must go with the process. A tunnel adapter left behind holds its
	// routes, and the machine keeps sending traffic somewhere that is gone.
	stdin.Close()
	done := make(chan error, 1)
	go func() { done <- client.Wait() }()
	select {
	case err := <-done:
		if err != nil {
			t.Errorf("the client exited badly: %v", err)
		}
	case <-time.After(20 * time.Second):
		client.Process.Kill()
		t.Fatal("the client did not exit when its parent closed stdin")
	}

	if adapterExists(t, "RelayTest") {
		t.Error("the adapter is still there after the client exited")
	}
}

// READY has to mean the peer answered, not merely that the adapter exists.
//
// Creating a WinTun adapter, addressing it and routing into it all succeed
// whether or not anything is listening at the far end, so for four releases the
// client reported READY the moment configureAdapter returned. The app took that
// as "Connected (Full Mode)" and — having no probe and no supervision — went on
// saying it over a tunnel that had never handshaked. The way in was ordinary:
// the phone mints fresh keys every time sharing restarts, so a QR scanned a few
// minutes earlier names keys the endpoint no longer has.
//
// Here nothing is listening on the endpoint port at all, which is the same thing
// from the client's side as a peer whose keys have moved on.
func TestReadyIsWithheldWhenThePeerNeverAnswers(t *testing.T) {
	binary := os.Getenv("RELAYWG_CLIENT")
	if binary == "" {
		t.Skip("RELAYWG_CLIENT is not set; CI builds the client and points this at it")
	}

	clientPrivate, _ := keyPair(t)
	_, serverPublic := keyPair(t)
	port := freeUDPPort(t) // deliberately nobody listening

	client := exec.Command(binary,
		"-name", "RelayTestDead",
		"-address", "10.13.37.2/32",
		"-routes", "198.51.100.0/24",
	)
	stdin, err := client.StdinPipe()
	if err != nil {
		t.Fatalf("stdin: %v", err)
	}
	stdout, err := client.StdoutPipe()
	if err != nil {
		t.Fatalf("stdout: %v", err)
	}
	client.Stderr = os.Stderr
	if err := client.Start(); err != nil {
		t.Fatalf("starting the client: %v", err)
	}
	defer func() {
		stdin.Close()
		client.Wait()
	}()

	fmt.Fprintf(stdin, "private_key=%s\npublic_key=%s\nendpoint=127.0.0.1:%d\n"+
		"allowed_ip=0.0.0.0/0\npersistent_keepalive_interval=1\n%s\n",
		clientPrivate, serverPublic, port, configTerminator)

	lines := make(chan string, 1)
	go func() {
		scanner := bufio.NewScanner(stdout)
		for scanner.Scan() {
			text := strings.TrimSpace(scanner.Text())
			// Informational, and it arrives first. The app skips it for the same
			// reason: it says something about the tunnel's protection, not about
			// whether the tunnel came up, and treating it as the verdict is what
			// this test did until it started failing on a line it had no opinion
			// about.
			if text == "" || text == leakProtectionFailedLine {
				continue
			}
			lines <- text
			return
		}
		lines <- ""
	}()

	select {
	case line := <-lines:
		if line == "READY" {
			t.Fatal("READY was reported for a tunnel whose peer never answered — " +
				"this is the bug where the app says Connected over a dead tunnel")
		}
		if line != noHandshakeLine {
			t.Fatalf("expected %q or a clean exit, got %q", noHandshakeLine, line)
		}
	case <-time.After(handshakeTimeout + 40*time.Second):
		client.Process.Kill()
		t.Fatal("the client neither reported the missing handshake nor exited")
	}

	if adapterExists(t, "RelayTestDead") {
		t.Error("the adapter is still there after the client gave up on the peer")
	}
}

func waitForReady(t *testing.T, stdout io.Reader, client *exec.Cmd) {
	t.Helper()
	ready := make(chan string, 1)
	go func() {
		scanner := bufio.NewScanner(stdout)
		for scanner.Scan() {
			// Reads until READY, so any informational line before it -- such as
			// leakProtectionFailedLine -- is skipped by construction.
			if strings.TrimSpace(scanner.Text()) == "READY" {
				ready <- "READY"
				return
			}
		}
		ready <- ""
	}()

	select {
	case line := <-ready:
		if line == "" {
			t.Fatal("the client exited before it was ready — most likely not running as Administrator")
		}
	case <-time.After(60 * time.Second):
		client.Process.Kill()
		t.Fatal("the client never reported READY")
	}
}

// adapterExists asks Windows, not the client, whether the adapter is gone.
func adapterExists(t *testing.T, name string) bool {
	t.Helper()
	// Give Windows a moment: the adapter is removed as the process exits, and
	// the interface list can lag that by a beat.
	for i := 0; i < 20; i++ {
		out, err := exec.Command("powershell", "-NoProfile", "-Command",
			fmt.Sprintf("if (Get-NetAdapter -Name '%s' -ErrorAction SilentlyContinue) { 'yes' } else { 'no' }", name),
		).Output()
		if err == nil && strings.TrimSpace(string(out)) == "no" {
			return false
		}
		time.Sleep(500 * time.Millisecond)
	}
	return true
}

func keyPair(t *testing.T) (private, public string) {
	t.Helper()
	var priv [32]byte
	if _, err := io.ReadFull(rand.Reader, priv[:]); err != nil {
		t.Fatalf("key generation: %v", err)
	}
	priv[0] &= 248
	priv[31] &= 127
	priv[31] |= 64
	pub, err := curve25519.X25519(priv[:], curve25519.Basepoint)
	if err != nil {
		t.Fatalf("public key: %v", err)
	}
	return hex.EncodeToString(priv[:]), hex.EncodeToString(pub)
}

func freeUDPPort(t *testing.T) int {
	t.Helper()
	c, err := net.ListenPacket("udp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("no free port: %v", err)
	}
	defer c.Close()
	return c.LocalAddr().(*net.UDPAddr).Port
}

// Leak protection must not take localhost down with IPv6.
//
// The IPv6 rule is written as "all of ALE_AUTH_CONNECT_V6", and WFP classifies
// loopback at that layer too -- so the first version of it blocked ::1 as well.
// On Windows "localhost" resolves to ::1 before 127.0.0.1, which meant that
// while Relay was connected, every localhost connection on the machine failed
// or stalled: development servers, database clients, desktop apps talking to
// their own helpers. Unrelated software breaking, blamed on the tunnel.
//
// This is the only place that can be caught. The endpoint's suite runs on Linux
// with no WFP at all, and the app's own tests never start the tunnel; a runner
// with Administrator and a real filtering engine is the whole point.
func TestLeakProtectionLeavesLoopbackAlone(t *testing.T) {
	binary := os.Getenv("RELAYWG_CLIENT")
	if binary == "" {
		t.Skip("RELAYWG_CLIENT is not set; CI builds the client and points this at it")
	}
	if !ipv6LoopbackWorks(t) {
		t.Skip("this machine cannot reach ::1 even without the tunnel")
	}

	_, serverPublic := keyPair(t)
	clientPrivate, _ := keyPair(t)

	// No endpoint on the other end: the filters are installed before the
	// handshake is waited for, so they are live for the whole timeout and this
	// never needs a peer that answers.
	client := exec.Command(binary,
		"-name", "RelayTestLoopback",
		"-address", "10.13.37.2/32",
		"-routes", "198.51.100.0/24",
		"-dns", "1.1.1.1",
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
	defer func() {
		_ = client.Process.Kill()
		_, _ = client.Process.Wait()
	}()

	fmt.Fprintf(stdin, "private_key=%s\npublic_key=%s\nendpoint=127.0.0.1:9\nallowed_ip=0.0.0.0/0\n%s\n",
		clientPrivate, serverPublic, configTerminator)

	// Wait for the filters to be in place rather than for a fixed delay: the
	// window this is testing opens exactly when that line is printed.
	protected := make(chan bool, 1)
	go func() {
		scanner := bufio.NewScanner(stderr)
		for scanner.Scan() {
			line := scanner.Text()
			if strings.Contains(line, "leak protection on") {
				protected <- true
				return
			}
			if strings.Contains(line, "leak protection unavailable") {
				protected <- false
				return
			}
		}
		protected <- false
	}()

	select {
	case on := <-protected:
		if !on {
			t.Skip("leak protection did not install here; nothing to assert about it")
		}
	case <-time.After(30 * time.Second):
		t.Fatal("the client never said whether leak protection was installed")
	}

	if !ipv6LoopbackWorks(t) {
		t.Fatal("::1 stopped working while leak protection was on — " +
			"this blocks localhost for every application on the machine")
	}
}

// ipv6LoopbackWorks reports whether a TCP connection to ::1 completes.
func ipv6LoopbackWorks(t *testing.T) bool {
	t.Helper()

	listener, err := net.Listen("tcp6", "[::1]:0")
	if err != nil {
		return false
	}
	defer listener.Close()
	go func() {
		if conn, err := listener.Accept(); err == nil {
			conn.Close()
		}
	}()

	conn, err := net.DialTimeout("tcp6", listener.Addr().String(), 5*time.Second)
	if err != nil {
		return false
	}
	conn.Close()
	return true
}
