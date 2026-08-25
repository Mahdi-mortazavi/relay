package relaywg

import (
	crand "crypto/rand"
	"encoding/hex"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/netip"
	"strings"
	"testing"
	"time"

	"golang.org/x/crypto/curve25519"
	"golang.zx2c4.com/wireguard/conn"
	"golang.zx2c4.com/wireguard/device"
	"golang.zx2c4.com/wireguard/tun/netstack"
)

// The ADR says only hardware can prove the live tunnel. That is true of a real
// phone's radio and a real PC's adapter — but the part that is actually novel
// here, the forwarding endpoint, is provable in a process: stand up the real
// endpoint, dial it with a real wireguard-go client over loopback UDP, and ask
// for a page from a server that is only reachable outside the tunnel.
//
// If bytes come back, the tunnel terminated, the netstack accepted a packet
// addressed to somewhere it does not own, the forwarder opened a real socket,
// and the reply made it home. That is the whole feature.

func keyPair(t testing.TB) (private, public string) {
	t.Helper()
	var priv [32]byte
	if _, err := io.ReadFull(crand.Reader, priv[:]); err != nil {
		t.Fatalf("key generation: %v", err)
	}
	// Curve25519 clamping, as WireGuard requires.
	priv[0] &= 248
	priv[31] &= 127
	priv[31] |= 64

	pub, err := curve25519.X25519(priv[:], curve25519.Basepoint)
	if err != nil {
		t.Fatalf("public key: %v", err)
	}
	return hex.EncodeToString(priv[:]), hex.EncodeToString(pub)
}

func freeUDPPort(t testing.TB) int {
	t.Helper()
	c, err := net.ListenPacket("udp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("no free port: %v", err)
	}
	defer c.Close()
	return c.LocalAddr().(*net.UDPAddr).Port
}

func TestForwardsRealTrafficOutOfTheTunnel(t *testing.T) {
	// A destination that exists only outside the tunnel.
	destination := httpServer(t, "hello-through-the-tunnel")

	serverPrivate, serverPublic := keyPair(t)
	clientPrivate, clientPublic := keyPair(t)
	port := freeUDPPort(t)

	endpoint, err := Start(fmt.Sprintf(
		"private_key=%s\nlisten_port=%d\npublic_key=%s\nallowed_ip=10.13.37.2/32\n",
		serverPrivate, port, clientPublic))
	if err != nil {
		t.Fatalf("endpoint did not start: %v", err)
	}
	defer endpoint.Stop()

	client, clientNet := wireguardClient(t, clientPrivate, serverPublic, port)
	defer client.Close()

	// Everything below goes through the tunnel.
	httpClient := &http.Client{
		Transport: &http.Transport{DialContext: clientNet.DialContext},
		Timeout:   15 * time.Second,
	}

	var body string
	// The handshake takes a moment; retry briefly rather than racing it.
	deadline := time.Now().Add(20 * time.Second)
	for time.Now().Before(deadline) {
		response, err := httpClient.Get("http://" + destination + "/")
		if err == nil {
			raw, _ := io.ReadAll(response.Body)
			response.Body.Close()
			body = string(raw)
			break
		}
		time.Sleep(250 * time.Millisecond)
	}

	if body != "hello-through-the-tunnel" {
		t.Fatalf("nothing came back through the tunnel (got %q)", body)
	}
}

// UDP is the reason Full Mode exists. Fast Mode's SOCKS5 path carries TCP only,
// so if this does not work the mode has no purpose.
func TestForwardsUdpOutOfTheTunnel(t *testing.T) {
	echo := udpEchoServer(t)

	serverPrivate, serverPublic := keyPair(t)
	clientPrivate, clientPublic := keyPair(t)
	port := freeUDPPort(t)

	endpoint, err := Start(fmt.Sprintf(
		"private_key=%s\nlisten_port=%d\npublic_key=%s\nallowed_ip=10.13.37.2/32\n",
		serverPrivate, port, clientPublic))
	if err != nil {
		t.Fatalf("endpoint did not start: %v", err)
	}
	defer endpoint.Stop()

	client, clientNet := wireguardClient(t, clientPrivate, serverPublic, port)
	defer client.Close()

	deadline := time.Now().Add(20 * time.Second)
	for time.Now().Before(deadline) {
		conn, err := clientNet.Dial("udp", echo)
		if err != nil {
			time.Sleep(200 * time.Millisecond)
			continue
		}
		_ = conn.SetDeadline(time.Now().Add(2 * time.Second))
		if _, err := conn.Write([]byte("ping")); err != nil {
			conn.Close()
			time.Sleep(200 * time.Millisecond)
			continue
		}
		reply := make([]byte, 16)
		n, err := conn.Read(reply)
		conn.Close()
		if err == nil && string(reply[:n]) == "ping" {
			return // the datagram went out and came back
		}
		time.Sleep(200 * time.Millisecond)
	}
	t.Fatal("no UDP datagram made it back through the tunnel")
}

func udpEchoServer(t *testing.T) string {
	t.Helper()
	conn, err := net.ListenPacket("udp", nonLoopbackAddress(t)+":0")
	if err != nil {
		t.Fatalf("udp echo server: %v", err)
	}
	t.Cleanup(func() { conn.Close() })
	go func() {
		buf := make([]byte, 2048)
		for {
			n, from, err := conn.ReadFrom(buf)
			if err != nil {
				return
			}
			_, _ = conn.WriteTo(buf[:n], from)
		}
	}()
	return conn.LocalAddr().String()
}

func TestStartRejectsAnEmptyConfiguration(t *testing.T) {
	if _, err := Start(""); err == nil {
		t.Fatal("an empty configuration must not produce a running endpoint")
	}
}

func TestStartRejectsNonsense(t *testing.T) {
	// A malformed config must fail loudly at start, not leave a dead endpoint
	// that looks alive and drops everything.
	if _, err := Start("this is not a wireguard configuration\n"); err == nil {
		t.Fatal("a malformed configuration must not produce a running endpoint")
	}
}

func TestStopIsSafeTwiceAndOnNil(t *testing.T) {
	// Teardown runs on paths already unwinding from an error; a panic there
	// would take the app down with it.
	var absent *Endpoint
	absent.Stop()

	serverPrivate, _ := keyPair(t)
	_, clientPublic := keyPair(t)
	endpoint, err := Start(fmt.Sprintf(
		"private_key=%s\nlisten_port=%d\npublic_key=%s\nallowed_ip=10.13.37.2/32\n",
		serverPrivate, freeUDPPort(t), clientPublic))
	if err != nil {
		t.Fatalf("endpoint did not start: %v", err)
	}
	endpoint.Stop()
	endpoint.Stop()
}

// --- helpers -----------------------------------------------------------------

// nonLoopbackAddress finds an address on this machine that is routable from the
// stack's point of view.
//
// The obvious way is to enumerate interfaces, and on Android that quietly finds
// nothing: since API 30 the platform blocks netlink interface enumeration for
// anything but system UIDs, so net.InterfaceAddrs returns loopback alone. This
// suite is cross-compiled and run on a device precisely to prove the tunnel
// works there — and with enumeration as the only route, the three tests that
// carry real traffic skipped themselves on the device and the job went green
// having proven nothing. A skip that reads as a pass is worse than a failure.
//
// Opening a UDP socket toward a routable address asks the kernel the same
// question without netlink: nothing is sent, but the socket is bound to the
// address the route would use.
func nonLoopbackAddress(t testing.TB) string {
	t.Helper()

	// Retried, because on a freshly booted emulator the default route appears a
	// moment after the shell does, and the first attempt then fails with
	// "network is unreachable". One run of this job passed and the next skipped
	// all three traffic tests for exactly that reason.
	deadline := time.Now().Add(20 * time.Second)
	for {
		if address := routableAddress(); address != "" {
			return address
		}
		if time.Now().After(deadline) {
			// Not t.Skip. A skip here reads as a pass, and these are the only
			// tests in the suite that put real traffic through the tunnel:
			// skipping them leaves a green run that has proven nothing about
			// the thing it exists to prove.
			t.Fatal("no routable IPv4 address after 20s — the tests that carry " +
				"traffic cannot run, and a skipped run of them is not a passing one")
		}
		time.Sleep(250 * time.Millisecond)
	}
}

// routableAddress returns this machine's address as the routing table sees it,
// or "" if there is none yet.
func routableAddress() string {
	// Opening a UDP socket toward a routable address asks the kernel the same
	// question interface enumeration would, without netlink: nothing is sent,
	// but the socket binds to the address the route would use. 192.0.2.1 is the
	// reserved documentation range, so this cannot reach anything real.
	if conn, err := net.Dial("udp4", "192.0.2.1:9"); err == nil {
		defer conn.Close()
		if addr, ok := conn.LocalAddr().(*net.UDPAddr); ok && !addr.IP.IsLoopback() {
			if v4 := addr.IP.To4(); v4 != nil {
				return v4.String()
			}
		}
	}

	// Enumeration as a fallback, for anywhere the dial is refused.
	interfaces, err := net.InterfaceAddrs()
	if err != nil {
		return ""
	}
	for _, addr := range interfaces {
		if ipNet, ok := addr.(*net.IPNet); ok && !ipNet.IP.IsLoopback() {
			if v4 := ipNet.IP.To4(); v4 != nil {
				return v4.String()
			}
		}
	}
	return ""
}

func httpServer(t testing.TB, body string) string {
	t.Helper()
	// Deliberately not loopback. gVisor drops packets addressed to 127.0.0.0/8
	// that arrive on an ordinary NIC, so a destination on loopback would make
	// this test fail for a reason that has nothing to do with the endpoint --
	// and a phone forwarding to the internet never sees a loopback destination
	// anyway.
	listener, err := net.Listen("tcp", nonLoopbackAddress(t)+":0")
	if err != nil {
		t.Fatalf("destination server: %v", err)
	}
	server := &http.Server{
		Handler: http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
			fmt.Fprint(w, body)
		}),
	}
	go server.Serve(listener)
	t.Cleanup(func() { server.Close() })
	return listener.Addr().String()
}

// wireguardClient is an ordinary wireguard-go client — the same shape the
// Windows app will be — so the test exercises the endpoint through the real
// protocol rather than through anything built for testing.
func wireguardClient(t testing.TB, privateKey, serverPublic string, port int) (*device.Device, *netstack.Net) {
	t.Helper()
	address := netip.MustParseAddr("10.13.37.2")
	tunDevice, tunNet, err := netstack.CreateNetTUN([]netip.Addr{address}, nil, mtu)
	if err != nil {
		t.Fatalf("client stack: %v", err)
	}
	dev := device.NewDevice(tunDevice, conn.NewDefaultBind(),
		device.NewLogger(device.LogLevelError, "client: "))

	config := strings.Join([]string{
		"private_key=" + privateKey,
		"public_key=" + serverPublic,
		"endpoint=127.0.0.1:" + fmt.Sprint(port),
		"allowed_ip=0.0.0.0/0",
		"persistent_keepalive_interval=1",
		"",
	}, "\n")
	if err := dev.IpcSet(config); err != nil {
		t.Fatalf("client configuration: %v", err)
	}
	if err := dev.Up(); err != nil {
		t.Fatalf("client did not come up: %v", err)
	}
	return dev, tunNet
}

func TestReportsThePeerOnceItHasArrived(t *testing.T) {
	// The phone's screen depends on this. A UDP port answers the same whether
	// or not a laptop is behind it, so "someone connected" can only come from
	// the handshake -- and if it never appears, Full Mode says "waiting for a
	// PC" through an entire download.
	destination := httpServer(t, "counted")

	serverPrivate, serverPublic := keyPair(t)
	clientPrivate, clientPublic := keyPair(t)
	port := freeUDPPort(t)

	endpoint, err := Start(fmt.Sprintf(
		"private_key=%s\nlisten_port=%d\npublic_key=%s\nallowed_ip=10.13.37.2/32\n",
		serverPrivate, port, clientPublic))
	if err != nil {
		t.Fatalf("endpoint did not start: %v", err)
	}
	defer endpoint.Stop()

	if got := endpoint.LastHandshakeUnix(); got != 0 {
		t.Errorf("claimed a handshake at %d before any client existed", got)
	}

	client, clientNet := wireguardClient(t, clientPrivate, serverPublic, port)
	defer client.Close()

	httpClient := &http.Client{
		Transport: &http.Transport{DialContext: clientNet.DialContext},
		Timeout:   15 * time.Second,
	}
	deadline := time.Now().Add(20 * time.Second)
	for time.Now().Before(deadline) {
		if response, err := httpClient.Get("http://" + destination + "/"); err == nil {
			io.Copy(io.Discard, response.Body)
			response.Body.Close()
			break
		}
		time.Sleep(250 * time.Millisecond)
	}

	if endpoint.LastHandshakeUnix() <= 0 {
		t.Error("traffic crossed the tunnel and no handshake was reported")
	}
	if endpoint.BytesReceived() <= 0 || endpoint.BytesSent() <= 0 {
		t.Errorf("counters stayed empty after a transfer: rx=%d tx=%d",
			endpoint.BytesReceived(), endpoint.BytesSent())
	}

	// After teardown there is nothing to report, and asking must not panic --
	// the poll that drives the screen runs on its own thread and can easily
	// arrive one tick after Stop.
	endpoint.Stop()
	if got := endpoint.LastHandshakeUnix(); got != 0 {
		t.Errorf("a stopped endpoint reported a handshake at %d", got)
	}
}

func TestPeerCountersAreSafeWhenNothingIsRunning(t *testing.T) {
	// The gomobile wrappers read through a nil *Endpoint whenever the phone
	// polls between sessions; that has to be a zero, not a crash inside the
	// app's own process.
	StopEndpoint()
	if LastHandshakeUnix() != 0 || BytesReceived() != 0 || BytesSent() != 0 {
		t.Error("a stopped session reported traffic")
	}
}

func TestStartEndpointReplacesTheRunningOne(t *testing.T) {
	// A network change restarts sharing. If the old endpoint were left running
	// it would hold the UDP port and the new one would fail to bind, which
	// would look like "Full Mode stopped working after I switched Wi-Fi".
	defer StopEndpoint()

	serverPrivate, _ := keyPair(t)
	_, clientPublic := keyPair(t)
	port := freeUDPPort(t)
	config := fmt.Sprintf(
		"private_key=%s\nlisten_port=%d\npublic_key=%s\nallowed_ip=10.13.37.2/32\n",
		serverPrivate, port, clientPublic)

	if err := StartEndpoint(config); err != nil {
		t.Fatalf("first start: %v", err)
	}
	if !IsRunning() {
		t.Fatal("IsRunning says no after a successful start")
	}
	// The same port again: this can only succeed if the first was released.
	if err := StartEndpoint(config); err != nil {
		t.Fatalf("restart on the same port: %v", err)
	}
	if !IsRunning() {
		t.Fatal("IsRunning says no after a restart")
	}
}

func TestStopEndpointIsSafeWhenNothingIsRunning(t *testing.T) {
	StopEndpoint()
	StopEndpoint()
	if IsRunning() {
		t.Fatal("IsRunning says yes with nothing started")
	}
}

func TestAFailedStartLeavesNothingRunning(t *testing.T) {
	// Otherwise the app believes Full Mode is on, shows a QR for it, and every
	// connection silently goes nowhere.
	StopEndpoint()
	if err := StartEndpoint("nonsense\n"); err == nil {
		t.Fatal("a malformed configuration must not start")
	}
	if IsRunning() {
		t.Fatal("a failed start left an endpoint behind")
	}
}
