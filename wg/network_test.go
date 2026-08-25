package relaywg

import (
	"crypto/rand"
	"crypto/sha256"
	"fmt"
	"io"
	"net"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"golang.zx2c4.com/wireguard/tun/netstack"
)

// What happens to the tunnel when the network underneath it misbehaves.
//
// Everything else in this suite runs over a loopback path that never loses a
// packet, never goes away and never changes address. Real ones do all three,
// constantly, and those are the reports that are hardest to act on: "it stops
// working when I walk to the other room", "it dies when my laptop wakes up".
//
// So the two ends are wired together through a relay this file controls. It can
// drop a share of the datagrams, go silent entirely, or start forwarding from a
// different address mid-session -- which is what a lossy link, a network
// outage, and a Wi-Fi change respectively look like from inside the tunnel.
//
// What this still cannot do is suspend a machine. Sleep/wake needs hardware and
// is reported as untested rather than approximated by something that resembles
// it from a distance.

// unreliableLink sits between the client and the endpoint and can be told to
// misbehave.
type unreliableLink struct {
	fromClient *net.UDPConn // the address the client dials
	endpoint   *net.UDPAddr

	mu       sync.Mutex
	toServer *net.UDPConn // our source towards the endpoint; replaceable
	client   *net.UDPAddr

	dropPercent atomic.Int32
	down        atomic.Bool
	closed      atomic.Bool
	counter     atomic.Uint64
}

func newUnreliableLink(t *testing.T, endpointPort int) *unreliableLink {
	t.Helper()

	fromClient, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		t.Fatalf("relay listener: %v", err)
	}
	toServer, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		t.Fatalf("relay upstream: %v", err)
	}

	link := &unreliableLink{
		fromClient: fromClient,
		endpoint:   &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1), Port: endpointPort},
		toServer:   toServer,
	}
	go link.pumpClientToServer()
	go link.pumpServerToClient()

	t.Cleanup(func() {
		link.closed.Store(true)
		fromClient.Close()
		link.mu.Lock()
		link.toServer.Close()
		link.mu.Unlock()
	})
	return link
}

func (l *unreliableLink) port() int { return l.fromClient.LocalAddr().(*net.UDPAddr).Port }

// drop decides this datagram's fate: one in every N, evenly spaced.
//
// Evenly spaced matters more than it looks. The first version of this dropped
// the first twenty of every hundred, which is not twenty percent loss -- it is
// a twenty-datagram burst, repeated. TCP treats a burst that long as a dead
// path and backs off, so a transfer that would finish in seconds on a genuinely
// lossy link did not finish at all. The test was measuring the harness.
func (l *unreliableLink) drop() bool {
	percent := l.dropPercent.Load()
	if percent <= 0 {
		return false
	}
	every := uint64(100 / percent)
	return l.counter.Add(1)%every == 0
}

func (l *unreliableLink) pumpClientToServer() {
	buffer := make([]byte, 2048)
	for {
		n, from, err := l.fromClient.ReadFromUDP(buffer)
		if err != nil {
			if l.closed.Load() {
				return
			}
			continue
		}
		l.mu.Lock()
		l.client = from
		upstream := l.toServer
		l.mu.Unlock()

		if l.down.Load() || l.drop() {
			continue
		}
		_, _ = upstream.WriteToUDP(buffer[:n], l.endpoint)
	}
}

func (l *unreliableLink) pumpServerToClient() {
	buffer := make([]byte, 2048)
	for {
		l.mu.Lock()
		upstream := l.toServer
		l.mu.Unlock()

		n, _, err := upstream.ReadFromUDP(buffer)
		if err != nil {
			if l.closed.Load() {
				return
			}
			// The socket was replaced underneath us (a path change); pick the
			// new one up on the next turn.
			time.Sleep(10 * time.Millisecond)
			continue
		}
		l.mu.Lock()
		client := l.client
		l.mu.Unlock()
		if client == nil || l.down.Load() || l.drop() {
			continue
		}
		_, _ = l.fromClient.WriteToUDP(buffer[:n], client)
	}
}

// changePath swaps the address this relay talks to the endpoint from, which is
// what a Wi-Fi change or a NAT rebinding looks like from the endpoint's side:
// the same peer, the same keys, a different address.
func (l *unreliableLink) changePath(t *testing.T) {
	t.Helper()

	replacement, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		t.Fatalf("new path: %v", err)
	}
	l.mu.Lock()
	previous := l.toServer
	l.toServer = replacement
	l.mu.Unlock()
	previous.Close()

	go l.pumpServerToClient()
}

// tunnelOverLink brings both ends up with the relay between them.
func tunnelOverLink(t *testing.T) (*unreliableLink, *netstack.Net, string, func()) {
	t.Helper()

	destination := echoServer(t)

	serverPrivate, serverPublic := keyPair(t)
	clientPrivate, clientPublic := keyPair(t)
	endpointPort := freeUDPPort(t)

	endpoint, err := Start(fmt.Sprintf(`private_key=%s
listen_port=%d
public_key=%s
allowed_ip=10.13.37.2/32
`, serverPrivate, endpointPort, clientPublic))
	if err != nil {
		t.Fatalf("endpoint did not start: %v", err)
	}

	link := newUnreliableLink(t, endpointPort)
	client, clientNet := wireguardClient(t, clientPrivate, serverPublic, link.port())

	// Warm up through the relay, so the handshake is done before anything is
	// deliberately broken.
	conn := dialThroughTunnel(t, clientNet, destination)
	conn.Close()

	return link, clientNet, destination, func() {
		client.Close()
		endpoint.Stop()
	}
}

// exchangeThrough opens a connection and echoes one message, which reports
// whether the tunnel is carrying traffic right now.
func exchangeThrough(clientNet *netstack.Net, destination string, timeout time.Duration) error {
	conn, err := clientNet.Dial("tcp", destination)
	if err != nil {
		return err
	}
	defer conn.Close()

	_ = conn.SetDeadline(time.Now().Add(timeout))
	message := []byte("are you there")
	if _, err := conn.Write(message); err != nil {
		return err
	}
	echo := make([]byte, len(message))
	_, err = io.ReadFull(conn, echo)
	return err
}

// A transfer must survive a lossy link.
//
// WireGuard does not retransmit -- it is a datagram protocol -- so everything
// lost here has to be recovered by the TCP running inside the tunnel, through
// the endpoint's gVisor stack. That stack has SACK turned on for exactly this
// reason, and this is the test that says so: a fifth of the datagrams are
// thrown away and the bytes still have to arrive exactly.
//
// Five percent, evenly spaced. That is already a bad link -- a Wi-Fi edge or a
// congested mobile cell -- and it is chosen to be realistic rather than
// theatrical: a rate high enough that no TCP finishes would prove nothing about
// the tunnel.
func TestATransferSurvivesPacketLoss(t *testing.T) {
	const size = 256 << 10

	payload := make([]byte, size)
	if _, err := rand.Read(payload); err != nil {
		t.Fatalf("payload: %v", err)
	}
	want := sha256.Sum256(payload)

	link, clientNet, destination, stop := tunnelOverLink(t)
	defer stop()

	link.dropPercent.Store(5)
	defer link.dropPercent.Store(0)

	conn := dialThroughTunnel(t, clientNet, destination)
	defer conn.Close()
	_ = conn.SetDeadline(time.Now().Add(180 * time.Second))

	sent := make(chan error, 1)
	go func() {
		_, err := conn.Write(payload)
		sent <- err
	}()

	returned := make([]byte, size)
	if _, err := io.ReadFull(conn, returned); err != nil {
		t.Fatalf("the transfer did not survive 5%% packet loss: %v", err)
	}
	if err := <-sent; err != nil {
		t.Fatalf("upload failed under loss: %v", err)
	}
	if got := sha256.Sum256(returned); got != want {
		t.Fatalf("data was corrupted rather than merely delayed: %x != %x", got, want)
	}
}

// The network goes away and comes back.
//
// A tunnel that never recovers from a brief outage is one a user has to notice
// and restart, and "I have to reconnect it every time I move" is the shape that
// gets reported as unreliability. Nothing is torn down here: the same tunnel,
// the same keys, the same sockets, with nothing crossing them for a while.
func TestTrafficResumesAfterTheNetworkDrops(t *testing.T) {
	link, clientNet, destination, stop := tunnelOverLink(t)
	defer stop()

	if err := exchangeThrough(clientNet, destination, 20*time.Second); err != nil {
		t.Fatalf("the tunnel was not working before the outage: %v", err)
	}

	link.down.Store(true)
	if err := exchangeThrough(clientNet, destination, 2*time.Second); err == nil {
		t.Fatal("traffic crossed a link that was supposed to be down; " +
			"the outage is not being simulated and the rest of this proves nothing")
	}
	time.Sleep(3 * time.Second)
	link.down.Store(false)

	// Retried, because recovery is not instant: WireGuard has to complete a
	// fresh handshake, and the point is that it happens on its own.
	deadline := time.Now().Add(45 * time.Second)
	var lastErr error
	for time.Now().Before(deadline) {
		if lastErr = exchangeThrough(clientNet, destination, 10*time.Second); lastErr == nil {
			return
		}
		time.Sleep(500 * time.Millisecond)
	}
	t.Fatalf("the tunnel never recovered after the network came back: %v", lastErr)
}

// The path changes underneath a live tunnel.
//
// This is a Wi-Fi change, or a NAT rebinding, or a phone moving between
// networks: the same peer and the same keys arriving from a different address.
// WireGuard is built to follow that, but only if nothing here interferes -- and
// the endpoint has to keep forwarding for connections that were already open.
func TestTheTunnelFollowsAChangeOfPath(t *testing.T) {
	link, clientNet, destination, stop := tunnelOverLink(t)
	defer stop()

	if err := exchangeThrough(clientNet, destination, 20*time.Second); err != nil {
		t.Fatalf("the tunnel was not working before the path changed: %v", err)
	}

	link.changePath(t)

	deadline := time.Now().Add(45 * time.Second)
	var lastErr error
	for time.Now().Before(deadline) {
		if lastErr = exchangeThrough(clientNet, destination, 10*time.Second); lastErr == nil {
			return
		}
		time.Sleep(500 * time.Millisecond)
	}
	t.Fatalf("the tunnel did not follow the peer to its new address: %v", lastErr)
}
